"""Run the Ethio-ASR CTC ONNX model over a manifest once and cache per-window log-probs, so LM
decoding can be tuned without re-running the acoustic model.

    python tools/cache_ctc_logits.py --manifest <dir>/manifest.tsv --out <cache_dir> [--limit N]

manifest.tsv rows: <wav path relative to manifest dir>\t<lang>\t<reference text>
Writes <cache_dir>/<clip stem>.npz (arrays w0, w1, ... = log_softmax over the 409 vocab columns,
float16, one per piece exactly like server/asr_server.py (split_at_silence)) and <cache_dir>/index.json
(ref, audio_s, acoustic_ms incl. feature extraction, greedy hypothesis from Engine._decode).
"""
import argparse
import json
import os
import sys
import time

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "server"))
sys.path.insert(0, os.path.join(ROOT, "tests", "ai-quality"))
import asr_server  # noqa: E402
from evaluate import read_wav  # noqa: E402


def log_softmax(x: np.ndarray) -> np.ndarray:
    x = x - x.max(-1, keepdims=True)
    return x - np.log(np.exp(x).sum(-1, keepdims=True))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--model", default=os.path.join(ROOT, "dist", "release-models", "ethio-asr-ctc-v2-model_int8.onnx"))
    ap.add_argument("--vocab", default=os.path.join(ROOT, "dist", "models", "ethio-asr-ctc", "vocab.json"))
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    idx_path = os.path.join(a.out, "index.json")
    index = json.load(open(idx_path, encoding="utf-8")) if os.path.exists(idx_path) else {}
    eng = asr_server.Engine(a.model, a.vocab, a.threads)
    nv = len(eng.id2tok)
    base = os.path.dirname(os.path.abspath(a.manifest))
    rows = [l.rstrip("\n").split("\t") for l in open(a.manifest, encoding="utf-8") if l.strip()]
    if a.limit:
        rows = rows[: a.limit]
    for n, (rel, _lang, ref) in enumerate(rows):
        stem = os.path.splitext(os.path.basename(rel))[0]
        if stem in index:
            continue
        x, sr = read_wav(os.path.join(base, rel))
        assert sr == asr_server.SR
        t0 = time.time()
        wins, hyps = [], []
        for chunk in asr_server.split_at_silence(x):  # same pieces as the server
            if len(chunk) < asr_server.SR // 2:
                continue
            f = asr_server.features(chunk)
            feeds = {"input_features": f}
            if "attention_mask" in eng.inputs:
                dt = np.int64 if "int64" in eng.inputs["attention_mask"] else np.int32
                feeds["attention_mask"] = np.ones(f.shape[:2], dt)
            lg = eng.sess.run(None, feeds)[0][0]
            hyps.append(eng._decode(lg))
            wins.append(log_softmax(lg[:, :nv].astype(np.float32)))
        ms = (time.time() - t0) * 1000
        np.savez(os.path.join(a.out, stem + ".npz"), **{f"w{i}": w.astype(np.float16) for i, w in enumerate(wins)})
        index[stem] = {"ref": ref, "audio_s": len(x) / sr, "acoustic_ms": ms,
                       "greedy": " ".join(h for h in hyps if h).strip(), "frames": [int(w.shape[0]) for w in wins]}
        json.dump(index, open(idx_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"{n} {stem} {len(x)/sr:.1f}s {ms:.0f} ms frames={index[stem]['frames']}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
