# AI quality tests

Repeatable measurement of speech recognition and translation quality (spec section 23).

## Dataset

`dataset/manifest.tsv` — one utterance per line:

```
audio_path<TAB>language<TAB>reference_transcript[<TAB>reference_translation_en]
clips/am_0001.wav	am	እንዴት ነህ? ነገ እንገናኝ።	How are you? Let's meet tomorrow.
```

Requirements for a valid dataset:

* 16 kHz, 16-bit mono WAV (export with `ffmpeg -i in.m4a -ac 1 -ar 16000 out.wav`).
* Consented speakers only. Keep a `CONSENT.md` next to the clips with who recorded what and
  the permission they gave. Never commit clips of people who did not agree.
* Reference transcripts written by a fluent Amharic writer, NFC normalized, Ethiopic punctuation.
* Cover the test matrix from the spec: conversational vs formal, regional accents, male/female,
  fast/slow, Amharic mixed with English, names/places, numbers/dates, background noise, long
  sentences, short commands. A `category` column may be added as a 5th field.

The `dataset/` folder is git-ignored for audio (`*.wav`); only the manifest and consent file are
committed. Public alternatives for bootstrapping: FLEURS `am_et` (CC-BY-4.0) and Common Voice
Amharic (CC0) — check each license before redistribution.

## Running

```bash
pip install pywhispercpp sacrebleu
python tests/ai-quality/evaluate.py --model dist/models/ggml-small-amharic-q5_1.bin \
    --manifest tests/ai-quality/dataset/manifest.tsv --lang am --out docs/ai-quality-am.json
```

Outputs per-utterance WER/CER/latency/RTF and overall numbers. Record them in
`docs/TEST_RESULTS.md` with the exact model file SHA-256 and the hardware used.

Translation quality: run the translated outputs through `sacrebleu --metrics chrf` against the
reference translations, and have at least two fluent speakers rate adequacy/fluency on a 1–5
scale for a 50-sentence sample. Automatic metrics alone are not acceptance criteria.

On-device numbers: the app shows `inference ms · RTF · model` on every result card (Voice Typing
screen). Collect those for the same clips on low-end, mid-range and flagship phones.
