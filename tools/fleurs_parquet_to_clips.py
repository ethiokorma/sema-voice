"""Extract FLEURS clips from a downloaded HF parquet (google/fleurs parquet-data/am_et/<split>-*.parquet)
into 16 kHz 16-bit mono WAVs + manifest.tsv (same layout as tests/ai-quality manifests).

    python tools/fleurs_parquet_to_clips.py <parquet> <out_dir> <prefix> [--start 0] [--count 100]
"""
import argparse
import io
import os
import unicodedata
import wave

import numpy as np
import pyarrow.parquet as pq
import soundfile as sf


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("parquet"); ap.add_argument("out"); ap.add_argument("prefix")
    ap.add_argument("--start", type=int, default=0); ap.add_argument("--count", type=int, default=100)
    a = ap.parse_args()
    os.makedirs(os.path.join(a.out, "clips"), exist_ok=True)
    t = pq.read_table(a.parquet, columns=["id", "audio", "transcription"]).slice(a.start, a.count).to_pylist()
    rows = []
    for k, ex in enumerate(t):
        i = a.start + k
        arr, sr = sf.read(io.BytesIO(ex["audio"]["bytes"]), dtype="float32", always_2d=False)
        if arr.ndim > 1:
            arr = arr.mean(axis=1)
        if sr != 16000:
            arr = np.interp(np.arange(int(len(arr) * 16000 / sr)) / 16000.0, np.arange(len(arr)) / sr, arr).astype(np.float32)
        pcm = np.clip(arr * 32767.0, -32768, 32767).astype(np.int16)
        name = f"{a.prefix}_{i:03d}.wav"
        with wave.open(os.path.join(a.out, "clips", name), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(pcm.tobytes())
        ref = unicodedata.normalize("NFC", ex["transcription"]).replace("\t", " ").replace("\n", " ").strip()
        rows.append(f"clips/{name}\tam\t{ref}\n")
    open(os.path.join(a.out, "manifest.tsv"), "w", encoding="utf-8").writelines(rows)
    print("wrote", len(rows))


if __name__ == "__main__":
    main()
