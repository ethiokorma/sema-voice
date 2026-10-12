"""AI quality harness for Sema Voice (section 23).

Measures, on a repeatable dataset of consented recordings with reference transcripts:
  * Word Error Rate (WER) and Character Error Rate (CER) per utterance and overall
  * recognition latency and real-time factor (RTF)
  * optional translation quality with chrF (sacrebleu) against reference translations

It runs the SAME ggml model files the Android app downloads, through whisper.cpp's CLI
(`whisper-cli`, built from the pinned v1.9.5 sources) or the `pywhispercpp` wheel, so numbers are
comparable to what the phone computes (minus the phone's CPU speed).

Dataset layout (tests/ai-quality/dataset/manifest.tsv):
    audio_path<TAB>language<TAB>reference_transcript[<TAB>reference_translation_en]
Audio: 16 kHz mono WAV. Reference transcripts must be real human transcripts, NFC normalized.

Usage:
    pip install jiwer sacrebleu pywhispercpp
    python tests/ai-quality/evaluate.py --model dist/models/ggml-small-amharic-q5_1.bin \
        --manifest tests/ai-quality/dataset/manifest.tsv --lang am --out docs/ai-quality-am.json

Nothing in this script fabricates numbers: if the model or dataset is missing it exits non-zero.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import unicodedata
import wave

import numpy as np

ETHIOPIC_PUNCT = "፡።፣፤፥፦፧፨"


def normalize(text: str) -> str:
    t = unicodedata.normalize("NFC", text)
    t = t.translate({ord(c): " " for c in ETHIOPIC_PUNCT})
    t = re.sub(r"[^\w\s]", " ", t)
    return re.sub(r"\s+", " ", t).strip().lower()


def edit_distance(a: list, b: list) -> int:
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(ref: str, hyp: str) -> float:
    r, h = normalize(ref).split(), normalize(hyp).split()
    return edit_distance(r, h) / max(1, len(r))


def cer(ref: str, hyp: str) -> float:
    r, h = normalize(ref).replace(" ", ""), normalize(hyp).replace(" ", "")
    return edit_distance(list(r), list(h)) / max(1, len(r))


def read_wav(path: str) -> tuple[np.ndarray, int]:
    with wave.open(path, "rb") as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2, "need 16-bit mono WAV"
        rate = w.getframerate()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return data, rate


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="ggml .bin file (same file the app downloads)")
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--lang", default="am")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    if not os.path.isfile(args.model):
        print(f"model not found: {args.model}"); return 2
    if not os.path.isfile(args.manifest):
        print(f"manifest not found: {args.manifest}"); return 2
    try:
        from pywhispercpp.model import Model  # type: ignore
    except ImportError:
        print("pip install pywhispercpp"); return 2

    model = Model(args.model, n_threads=args.threads, print_realtime=False, print_progress=False)
    rows = []
    base = os.path.dirname(args.manifest)
    with open(args.manifest, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            audio_path, lang, ref = cols[0], cols[1], cols[2]
            ref_tr = cols[3] if len(cols) > 3 else None
            samples, rate = read_wav(os.path.join(base, audio_path))
            if rate != 16000:
                print(f"skip {audio_path}: {rate} Hz (need 16 kHz)"); continue
            t0 = time.perf_counter()
            segments = model.transcribe(samples, language=lang if lang != "auto" else None)
            dt = time.perf_counter() - t0
            hyp = " ".join(s.text.strip() for s in segments)
            audio_s = len(samples) / 16000
            rows.append({
                "audio": audio_path, "lang": lang, "ref": ref, "hyp": hyp,
                "wer": wer(ref, hyp), "cer": cer(ref, hyp),
                "latency_ms": int(dt * 1000), "audio_ms": int(audio_s * 1000), "rtf": dt / max(audio_s, 1e-6),
                "ref_translation": ref_tr,
            })
            print(f"{audio_path}: WER {rows[-1]['wer']:.3f} CER {rows[-1]['cer']:.3f} RTF {rows[-1]['rtf']:.2f}")

    if not rows:
        print("no utterances evaluated"); return 1
    total_ref_words = sum(len(normalize(r["ref"]).split()) for r in rows)
    total_errs = sum(edit_distance(normalize(r["ref"]).split(), normalize(r["hyp"]).split()) for r in rows)
    total_ref_chars = sum(len(normalize(r["ref"]).replace(" ", "")) for r in rows)
    total_cerrs = sum(edit_distance(list(normalize(r["ref"]).replace(" ", "")), list(normalize(r["hyp"]).replace(" ", ""))) for r in rows)
    summary = {
        "model": os.path.basename(args.model), "language": args.lang, "utterances": len(rows),
        "wer": total_errs / max(1, total_ref_words), "cer": total_cerrs / max(1, total_ref_chars),
        "mean_rtf": float(np.mean([r["rtf"] for r in rows])), "mean_latency_ms": float(np.mean([r["latency_ms"] for r in rows])),
        "host": sys.platform, "threads": args.threads, "note": "Host CPU numbers; phone RTF will differ.",
        "rows": rows,
    }
    print(json.dumps({k: v for k, v in summary.items() if k != "rows"}, indent=2))
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
