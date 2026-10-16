# AMHARIC_ASR_OPTIMIZATION

## 1. Failure analysis of the Whisper fallback (shook-tiny)

On the 30 FLEURS clips, the 9 clips with WER ≥ 1.0 fall into two groups.

* **Restart (6 clips).** The decoder transcribes the audio again with small spelling changes,
  for example "እነዚህ አንዳንዴ የሚጨአነቁ … እነዚህ አንዳንዴ የሚጨነቁ …".
  * The first and second halves of the text are 63–96 % similar (character Levenshtein).
  * Output reaches 11–25 Ge'ez characters per second. Human references range from 4.6 to 9.3
    characters per second.
* **Truncation or garbage (3 clips).** Output is too short, or wrong words fill the clip.
  Post-processing cannot recover these.

Exact n-gram loop detection catches only 1 of the 30 outputs. The restarts are near-duplicates,
not exact repeats.

## 2. RepetitionGuard (`core/common/RepetitionGuard.kt`, Python twin `tests/ai-quality/repetition_guard.py`)

Rules, in order:

1. **Restart cut.** For cut points between 25 % and 75 % of the words, compare the suffix with
   an equally long prefix. If similarity is at least 0.6, keep the first pass.
2. **Looping tail.** If an n-gram (n = 1–6) repeats at least 3 times at the end, keep the first
   occurrence.
   * Two repeats, such as the legitimate reduplication "ቀስ ቀስ", are never touched.
3. **Internal loops** are reported only, never removed.
4. **Character runs** of 8 or more identical characters collapse to 2.
5. **Plausibility.** More than 12 non-space characters per second of audio is flagged.

`needsReview = loop || tooLong`. The keyboard **never auto-inserts** a result that needs review
(`KeyboardController`). The raw text is kept in `DictationResult.transcript`.

Measured effect (raw WER is still the official figure):

| Model | WER raw | WER guarded | CER raw | CER guarded | Restart / loop clips trimmed |
|---|---|---|---|---|---|
| shook-tiny (Whisper tiny) | 0.812 | **0.618** | 0.609 | **0.403** | 7/30 |
| WAXAL tiny | 0.870 | 0.732 | 0.842 | 0.594 | 6/30 |
| Stock tiny | 1.287 | 1.287 | 1.663 | 1.663 | 1/30 (no false cuts) |
| CTC models | 0.219 / 0.221 | unchanged | — | — | 0 (CTC does not loop) |

## 3. Decoder settings (whisper.cpp, `sema_whisper_jni.cpp`)

| Setting | Value | Note |
|---|---|---|
| Strategy | greedy, `temperature_inc = 0.2` | Fallback is already on |
| `entropy_thold` | 2.0 (default 2.4) | Triggers the fallback earlier on low-diversity output |
| `logprob_thold` | -0.8 (default -1.0) | |
| `max_tokens` | 224 per segment | |
| `no_speech_thold` | 0.6 | |
| `suppress_blank`, `suppress_nst` | on | |

These values are set in the app. Their effect has **not yet been isolated**: the harness supports
`--param entropy_thold=2.0 --param logprob_thold=-0.8 --param max_tokens=224`, but that A/B run is
still pending (see `benchmarks/amharic-decoder-experiments.json`). Whisper remains the fallback,
so the CTC path matters more than tuning Whisper.

## 4. CTC path (the real fix)

`OnnxCtcEngine` runs w2v-BERT 2.0 CTC models (hohe, Ethio-ASR) through ONNX Runtime.

* **Front-end.** `W2vBertFeatureExtractor` is a pure-Kotlin port of `SeamlessM4TFeatureExtractor`:
  * Kaldi-style 80-bin log-mel filterbank, Povey window and pre-emphasis.
  * Per-bin normalisation (ddof = 1) and stacking of 2 frames into 160 dimensions.
  * Verified against the Python reference on real Amharic audio
    (`W2vBertFeatureExtractorTest`; mean absolute error below 1e-3).
* **Decoder.** `CtcDecoder` does greedy decoding: blank = `[PAD]` (408), `|` becomes a space,
  and `[AMH]`-style tags are dropped. No tokenizer model is needed.
* **Windowing.** Audio is processed in 20 s windows to bound attention memory on phones.
* **Routing.** `DictationPipeline` picks the engine from the model's `format`. Only one large
  model stays resident; the other engine is released. `ModelManager` ranks models by
  `qualityRank`, so the CTC model is chosen for Amharic once it is installed. Auto-detect mode
  keeps a multilingual Whisper model.
