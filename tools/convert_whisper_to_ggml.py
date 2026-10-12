"""Converts a Hugging Face Whisper fine-tune (e.g. an Amharic checkpoint) to whisper.cpp ggml,
quantizes it, computes SHA-256 and prints the ModelCatalog entry values.

Why this exists: stock Whisper scores >100% WER on Amharic (Whisper paper, Table 13), so Sema
Voice needs a fine-tuned checkpoint. Only use checkpoints whose weights carry a commercial
license (Apache-2.0 / MIT). Candidates verified on 2026-10-12 (see docs/MODEL_COMPATIBILITY.md):
  seyyaw/whisper-finetuned-amharic        (apache-2.0, whisper-small)
  mintesnotfikir/whisper-medium-amharic   (apache-2.0, whisper-medium)
  b1n1yam/shook-tiny-amharic-600hr        (apache-2.0, whisper-tiny)
  BuzzASR/amharic                         (MIT, whisper-large-v3, custom tokenizer -> extra care)

Usage (Python 3.10+, ~6 GB free disk for small, more for medium):
    pip install torch transformers huggingface_hub
    git clone https://github.com/openai/whisper  tools/openai-whisper      # assets only
    python tools/convert_whisper_to_ggml.py --repo seyyaw/whisper-finetuned-amharic \
        --size small --out dist/models --quantize q5_1

The quantize step needs the whisper.cpp `quantize` binary (build whisper.cpp natively with CMake,
or pass --no-quantize to publish the f16 file).  The script never uploads anything; publish the
resulting .bin to your own hosting (e.g. a Hugging Face repo you own) and put the printed URL,
size and SHA-256 into ModelCatalog / the remote catalog JSON.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request

WHISPER_CPP_TAG = "v1.9.5"
CONVERT_SCRIPT_URL = f"https://raw.githubusercontent.com/ggml-org/whisper.cpp/{WHISPER_CPP_TAG}/models/convert-h5-to-ggml.py"


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="Hugging Face repo id of the fine-tuned Whisper checkpoint")
    ap.add_argument("--size", required=True, choices=["tiny", "base", "small", "medium", "large-v3", "large-v3-turbo"])
    ap.add_argument("--out", default="dist/models")
    ap.add_argument("--quantize", default="q5_1", help="q5_1, q5_0, q8_0 or 'none'")
    ap.add_argument("--quantize-bin", default=os.environ.get("WHISPER_QUANTIZE_BIN", "quantize"))
    ap.add_argument("--openai-whisper-dir", default=os.path.join(os.path.dirname(__file__), "openai-whisper"))
    ap.add_argument("--lang", default="am")
    args = ap.parse_args()

    from huggingface_hub import snapshot_download  # noqa: WPS433 (optional dependency)

    os.makedirs(args.out, exist_ok=True)
    work = tempfile.mkdtemp(prefix="sema-whisper-")
    print(f"[1/5] downloading {args.repo} -> {work}")
    model_dir = snapshot_download(args.repo, local_dir=os.path.join(work, "hf"), allow_patterns=["*.json", "*.safetensors", "*.bin", "*.txt", "*.model"])

    # License sanity check: refuse non-commercial tags.
    readme = os.path.join(model_dir, "README.md")
    if os.path.exists(readme):
        head = open(readme, encoding="utf-8", errors="ignore").read(4000).lower()
        if "cc-by-nc" in head or "noncommercial" in head or "non-commercial" in head:
            print("ERROR: model card declares a non-commercial license; refusing to convert.")
            return 2

    if not os.path.isdir(args.openai_whisper_dir):
        print(f"ERROR: clone https://github.com/openai/whisper into {args.openai_whisper_dir} (only its assets are used)")
        return 2

    print("[2/5] fetching convert-h5-to-ggml.py from whisper.cpp", WHISPER_CPP_TAG)
    convert = os.path.join(work, "convert-h5-to-ggml.py")
    urllib.request.urlretrieve(CONVERT_SCRIPT_URL, convert)

    print("[3/5] converting to ggml f16")
    out_dir = os.path.join(work, "ggml")
    os.makedirs(out_dir, exist_ok=True)
    subprocess.check_call([sys.executable, convert, model_dir, args.openai_whisper_dir, out_dir])
    produced = [f for f in os.listdir(out_dir) if f.endswith(".bin")]
    if not produced:
        print("ERROR: conversion produced no .bin")
        return 1
    f16 = os.path.join(out_dir, produced[0])
    base_name = f"ggml-{args.size}-amharic"
    final = os.path.join(args.out, base_name + ".bin")
    shutil.move(f16, final)

    if args.quantize != "none":
        print(f"[4/5] quantizing {args.quantize}")
        q = os.path.join(args.out, f"{base_name}-{args.quantize}.bin")
        try:
            subprocess.check_call([args.quantize_bin, final, q, args.quantize])
            final = q
        except (OSError, subprocess.CalledProcessError) as e:
            print(f"WARNING: quantize failed ({e}); publishing the f16 file instead")
    else:
        print("[4/5] skipping quantization")

    print("[5/5] hashing")
    digest = sha256(final)
    size = os.path.getsize(final)
    entry = {
        "id": f"whisper-{args.size}-amharic-{args.quantize if args.quantize != 'none' else 'f16'}",
        "file": os.path.basename(final),
        "sizeBytes": size,
        "sha256": digest,
        "sourceRepo": args.repo,
        "whisperCppTag": WHISPER_CPP_TAG,
        "languages": [args.lang, "en"],
    }
    print(json.dumps(entry, indent=2))
    with open(os.path.join(args.out, os.path.basename(final) + ".json"), "w", encoding="utf-8") as f:
        json.dump(entry, f, indent=2)
    print(f"\nDone: {final}\nUpload it to your own hosting and copy sizeBytes/sha256 into ModelCatalog.")
    shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
