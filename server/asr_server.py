"""Sema Voice speech server: runs the best measured Amharic model (Ethio-ASR CTC, ONNX int8,
WER 0.222 on FLEURS) natively on this PC, so any browser (phone, iPhone, desktop) can use it.

    python server/asr_server.py --model dist/release-models/ethio-asr-ctc-v2-model_int8.onnx \
        --vocab dist/models/ethio-asr-ctc/vocab.json --port 8765 [--token SECRET]
        [--lm server/lm/amharic-wiki-3gram.npz <scratch>/amharic-web-3gram.npz]   # optional LM beam search

API (CORS open; every request except /v1/health needs "Authorization: Bearer <token>"):
    GET  /v1/health                -> {"ok": true, "model": ..., "busy": bool}
    POST /v1/transcribe            body: 16 kHz mono float32 little-endian samples
                                   (Content-Type: application/octet-stream) or a 16 kHz WAV file
                                   -> {"text": ..., "ms": ..., "audio_s": ...}
Audio is processed in memory and never written to disk or logged.
"""
import argparse
import io
import json
import os
import secrets
import sys
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import onnxruntime as ort

SR = 16000
MAX_SECONDS = 120
FRAME, HOP, NFFT, MELS, STRIDE = 400, 160, 512, 80, 2
# CTC + word 3-gram LM settings tuned on FLEURS am_et dev (docs/AMHARIC_LM.md); used only with --lm
LM_DEFAULTS = {"alpha": 0.35, "beta": 2.0, "beam": 16, "char_weight": 0.3, "char_bonus": 1.5}


def _mel_bank() -> np.ndarray:
    mel = lambda f: 1127.0 * np.log(1.0 + f / 700.0)
    lo, hi = mel(20.0), mel(8000.0)
    d = (hi - lo) / (MELS + 1)
    bins = mel(np.arange(NFFT // 2 + 1) * SR / NFFT)
    bank = np.zeros((MELS, NFFT // 2 + 1))
    for m in range(MELS):
        l, c, r = lo + m * d, lo + (m + 1) * d, lo + (m + 2) * d
        bank[m] = np.maximum(0, np.minimum((bins - l) / (c - l), (r - bins) / (r - c)))
    return bank


WINDOW = np.power(0.5 - 0.5 * np.cos(2 * np.pi * np.arange(FRAME) / (FRAME - 1)), 0.85)
MEL = _mel_bank()


def features(x: np.ndarray) -> np.ndarray:
    """w2v-BERT 2.0 front-end (same as web/ctc.js and the Kotlin W2vBertFeatureExtractor)."""
    if len(x) < FRAME:
        return np.zeros((1, 0, MELS * STRIDE), np.float32)
    t = 1 + (len(x) - FRAME) // HOP
    idx = np.arange(FRAME)[None, :] + HOP * np.arange(t)[:, None]
    fr = x.astype(np.float64)[idx] * 32768.0
    fr -= fr.mean(axis=1, keepdims=True)
    fr[:, 1:] -= 0.97 * fr[:, :-1].copy()
    fr[:, 0] *= 1 - 0.97
    spec = np.abs(np.fft.rfft(fr * WINDOW, n=NFFT)) ** 2
    lm = np.log(np.maximum(spec @ MEL.T, 1.192092955078125e-7))
    if t > 1:
        lm = (lm - lm.mean(0)) / np.sqrt(lm.var(0, ddof=1) + 1e-7)
    f = t // STRIDE
    return lm[: f * STRIDE].reshape(f, MELS * STRIDE)[None].astype(np.float32)


def split_at_silence(x: np.ndarray, max_s: float = 40.0, search_s: float = 10.0):
    """Splits long audio into pieces of at most max_s seconds, cutting at the quietest 0.3 s inside
    the last search_s seconds of each piece. Fixed 20 s cuts split words and cost about 1 WER point
    on FLEURS (0.222 whole-clip vs 0.234 with 20 s windows, 2026-10-16)."""
    out, start, n = [], 0, len(x)
    maxn, srch, hop = int(max_s * SR), int(search_s * SR), int(0.05 * SR)
    while n - start > maxn:
        lo, hi = start + maxn - srch, start + maxn
        seg = x[lo:hi]
        k = len(seg) // hop
        e = np.square(seg[: k * hop].reshape(k, hop)).mean(1)
        e = np.convolve(e, np.ones(6) / 6, mode="same")  # 0.3 s smoothing
        cut = lo + int(np.argmin(e)) * hop + hop // 2
        out.append(x[start:cut]); start = cut
    out.append(x[start:])
    return out


class Engine:
    def __init__(self, model: str, vocab: str, threads: int, lm_decoder=None):
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        self.sess = ort.InferenceSession(model, so, providers=["CPUExecutionProvider"])
        v = json.load(open(vocab, encoding="utf-8"))
        self.id2tok = {i: t for t, i in v.items()}
        self.blank = v["[PAD]"]
        self.inputs = {i.name: i.type for i in self.sess.get_inputs()}
        self.name = os.path.basename(model)
        self.lm = lm_decoder  # optional lm.decoder.LmDecoder (word n-gram beam search); None = greedy
        self.lock = threading.Lock()  # one transcription at a time keeps memory and latency predictable

    def _decode(self, logits: np.ndarray) -> str:
        out, prev = [], -1
        for i in logits.argmax(-1):
            i = int(i)
            if i != prev and i != self.blank:
                tok = self.id2tok.get(i, "")
                if not (tok.startswith("[") and tok.endswith("]")) and tok not in ("<s>", "</s>"):
                    out.append(" " if tok == "|" else tok)
            prev = i
        return " ".join("".join(out).split())

    def transcribe(self, x: np.ndarray) -> str:
        parts = []
        with self.lock:
            for chunk in split_at_silence(x):
                if len(chunk) < SR // 2:
                    continue
                f = features(chunk)
                feeds = {"input_features": f}
                if "attention_mask" in self.inputs:
                    dt = np.int64 if "int64" in self.inputs["attention_mask"] else np.int32
                    feeds["attention_mask"] = np.ones(f.shape[:2], dt)
                logits = self.sess.run(None, feeds)[0][0]
                parts.append(self.lm.decode(logits) if self.lm else self._decode(logits))
        return " ".join(p for p in parts if p).strip()


def read_audio(body: bytes, ctype: str) -> np.ndarray:
    if body[:4] == b"RIFF":
        with wave.open(io.BytesIO(body)) as w:
            if w.getframerate() != SR or w.getsampwidth() != 2:
                raise ValueError("WAV must be 16 kHz 16-bit")
            x = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768.0
            if w.getnchannels() > 1:
                x = x.reshape(-1, w.getnchannels()).mean(1)
            return x
    if len(body) % 4:
        raise ValueError("raw body must be float32 samples")
    return np.frombuffer(body, "<f4").astype(np.float32)


def make_handler(engine: Engine, token: str):
    class H(BaseHTTPRequestHandler):
        server_version = "SemaVoiceASR/1.0"

        def log_message(self, fmt, *args):  # no request logging: keeps audio metadata private
            pass

        def _send(self, code: int, obj: dict):
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self._cors()
            self.end_headers()
            self.wfile.write(data)

        def _cors(self):
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Max-Age", "86400")
            self.send_header("Cross-Origin-Resource-Policy", "cross-origin")

        def _authed(self) -> bool:
            got = self.headers.get("Authorization", "")
            return secrets.compare_digest(got, f"Bearer {token}")

        def do_OPTIONS(self):
            self.send_response(204)
            self._cors()
            self.end_headers()

        def do_GET(self):
            if self.path.split("?")[0] == "/v1/health":
                return self._send(200, {"ok": True, "model": engine.name, "busy": engine.lock.locked(),
                                        "decoder": "ctc-lm-beam" if engine.lm else "ctc-greedy",
                                        "auth": self._authed()})
            self._send(404, {"error": "not found"})

        def do_POST(self):
            if self.path.split("?")[0] != "/v1/transcribe":
                return self._send(404, {"error": "not found"})
            if not self._authed():
                return self._send(401, {"error": "bad or missing access key"})
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0 or n > MAX_SECONDS * SR * 4 + 1024:
                return self._send(413, {"error": f"audio must be 0-{MAX_SECONDS} s"})
            try:
                x = read_audio(self.rfile.read(n), self.headers.get("Content-Type", ""))
            except Exception as e:
                return self._send(400, {"error": str(e)})
            t0 = time.time()
            text = engine.transcribe(np.clip(x, -1, 1))
            self._send(200, {"text": text, "ms": int((time.time() - t0) * 1000), "audio_s": round(len(x) / SR, 2)})

    return H


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.path.join(root, "dist", "release-models", "ethio-asr-ctc-v2-model_int8.onnx"))
    ap.add_argument("--vocab", default=os.path.join(root, "dist", "models", "ethio-asr-ctc", "vocab.json"))
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--threads", type=int, default=os.cpu_count() or 4)
    ap.add_argument("--token", default=os.environ.get("SEMA_TOKEN"))
    ap.add_argument("--lm", nargs="+", help="word 3-gram LM .npz file(s) from tools/build_amharic_lm.py -> CTC beam "
                    "search instead of greedy; tuned setup: server/lm/amharic-wiki-3gram.npz amharic-web-3gram.npz")
    ap.add_argument("--lm-weights", type=float, nargs="*", help="log-linear weights per LM (default equal)")
    ap.add_argument("--lm-alpha", type=float, default=LM_DEFAULTS["alpha"])
    ap.add_argument("--lm-beta", type=float, default=LM_DEFAULTS["beta"])
    ap.add_argument("--lm-beam", type=int, default=LM_DEFAULTS["beam"])
    ap.add_argument("--lm-char-weight", type=float, default=LM_DEFAULTS["char_weight"])
    ap.add_argument("--lm-char-bonus", type=float, default=LM_DEFAULTS["char_bonus"])
    a = ap.parse_args()
    token_file = os.path.join(root, "server", ".token")
    if not a.token:
        if os.path.exists(token_file):
            a.token = open(token_file).read().strip()
        else:
            a.token = secrets.token_urlsafe(18)
            open(token_file, "w").write(a.token)
    dec = None
    if a.lm:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from lm.decoder import LmDecoder
        dec = LmDecoder(a.lm, a.vocab, alpha=a.lm_alpha, beta=a.lm_beta, beam=a.lm_beam, char_weight=a.lm_char_weight,
                        char_bonus=a.lm_char_bonus, lm_weights=a.lm_weights)
        print(f"LM beam search: {[os.path.basename(p) for p in a.lm]} alpha={a.lm_alpha} beta={a.lm_beta} "
              f"beam={a.lm_beam}", flush=True)
    engine = Engine(a.model, a.vocab, a.threads, dec)
    print(f"Sema Voice ASR server on http://{a.host}:{a.port}  model={engine.name}", flush=True)
    print(f"Access key: {a.token}", flush=True)
    ThreadingHTTPServer((a.host, a.port), make_handler(engine, a.token)).serve_forever()


if __name__ == "__main__":
    sys.exit(main())
