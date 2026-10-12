"""Regenerates core/translation/src/test/resources/spm_fixtures.json using the reference
SentencePiece implementation so the Kotlin tokenizer is tested against ground truth.

    pip install sentencepiece
    python tools/gen_spm_fixtures.py
"""
import io
import json
import os

import sentencepiece as spm

ROOT = os.path.join(os.path.dirname(__file__), "..", "core", "translation", "src", "test", "resources")

SENTENCES = [
    "Hello, how are you?",
    "እንዴት ነህ? ነገ እንገናኝ።",
    "ነገ meeting አለኝ ከ manager ጋር።",
    "Addis Ababa is the capital of Ethiopia.",
    "ሰላም! Tewodros ይባላል።",
    "  multiple   spaces here ",
    "123 ብር ነው",
    "Let's meet tomorrow at 10:30, okay?",
    "ጤና ይስጥልኝ",
    "Soomaali waa luuqad.",
    "مرحبا بكم في أديس أبابا",
]

MODELS = {
    "m2m100": "m2m100/sentencepiece.bpe.model",
    "opus-mul-en-source": "opus-mul-en/source.spm",
    "opus-mul-en-target": "opus-mul-en/target.spm",
    "opus-en-mul-source": "opus-en-mul/source.spm",
}


def main() -> None:
    out = []
    for name, rel in MODELS.items():
        sp = spm.SentencePieceProcessor(model_file=os.path.join(ROOT, rel))
        for s in SENTENCES:
            pieces = sp.encode(s, out_type=str)
            out.append({"model": name, "text": s, "pieces": pieces, "decoded": sp.decode(pieces)})
    with io.open(os.path.join(ROOT, "spm_fixtures.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps(out, ensure_ascii=False, indent=0))
    print(f"wrote {len(out)} cases")


if __name__ == "__main__":
    main()
