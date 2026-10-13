# MODEL_COMPATIBILITY

Status of every model family, what has been verified, and what still needs a physical device.
Last updated 2026-10-12.

## Device requirements

| Requirement | Value | Why |
|---|---|---|
| Android | 8.0 (API 26) minimum, targets API 35 | `AudioRecord`, Keystore AES-GCM, foreground service types |
| ABI | arm64-v8a or x86_64 (emulator). armeabi-v7a and x86 are not shipped | ggml performance and 16 KB page compliance |
| CPU (arm64) | ARMv8.2-A with `fphp` and `asimddp` (Cortex-A55/A75 or newer, i.e. almost every phone since 2018) | `libsema_whisper.so` is compiled with `-march=armv8.2-a+fp16+dotprod`. Older cores (Snapdragon 835, Kirin 970, Exynos 8895) are detected at runtime and get an "unsupported device" message instead of a crash |
| 16 KB pages | Compliant (`-Wl,-z,max-page-size=16384`; verified with `llvm-readelf -l`: LOAD align 0x4000) | Google Play requirement for targetSdk 35 |
| RAM | see per-model table; the manager keeps 35 % of RAM free for the OS and the host app | keyboards run inside other apps' UI budget |

Performance tiers (`CompatibilityRules.tierFor`): LOW < 3.5 GB RAM or < 6 cores; MID ≥ 3.5 GB and
≥ 6 cores; HIGH ≥ 6 GB and ≥ 8 cores.

## Speech models (whisper.cpp ggml)

| Model | Download | RAM | Tier | Languages enabled | Status |
|---|---|---|---|---|---|
| whisper-tiny-q5_1 | 31 MB | ~180 MB | LOW | en, ar, so | checksum verified; inference not yet measured on hardware |
| whisper-base-q5_1 (default) | 57 MB | ~300 MB | LOW | en, ar, so | same |
| whisper-small-q5_1 | 181 MB | ~700 MB | MID | en, ar, so | same |
| whisper-medium-q5_0 | 514 MB | ~1.6 GB | HIGH | en, ar, so | same |
| whisper-large-v3-turbo-q5_0 | 547 MB | ~2.2 GB | HIGH | en, ar, so | same |
| tiny-amharic-q5_1 (b1n1yam/shook-tiny-amharic-600hr, converted) | 31 MB | ~180 MB | LOW | **am**, en | converted + measured (WER 0.81 raw on FLEURS, see TEST_RESULTS); hidden in the app until the publisher hosts `dist/models/ggml-tiny-amharic-q5_1.bin` + `catalog.json` |
| seyyaw/whisper-finetuned-amharic (small) | — | — | — | — | **rejected after measurement**: emits Hebrew/Latin garbage for Amharic audio |

**Amharic is deliberately NOT listed for the stock models.** The Whisper paper (Table 13) reports
FLEURS Amharic WER of 122.9 (tiny), 196.8 (base), 120.2 (small), 229.3 (medium), 140.3
(large-v2): the output is unusable. Somali is also above 100 % WER on FLEURS for every stock size,
so Somali is exposed only as "experimental" through the auto/English models and should be
re-evaluated; it is kept in the language list because Whisper does support the language code.

### Getting a working Amharic model

1. Pick a commercially licensed fine-tune. Verified candidates (model-card license, 2026-10-12):
   * `seyyaw/whisper-finetuned-amharic` (Apache-2.0, whisper-small, no WER reported)
   * `mintesnotfikir/whisper-medium-amharic` (Apache-2.0, whisper-medium, no WER reported)
   * `b1n1yam/shook-tiny-amharic-600hr` (Apache-2.0, whisper-tiny)
   * `BuzzASR/amharic` (MIT, whisper-large-v3, FLEURS WER 33.9 reported by the authors; 3 GB fp16,
     custom tokenizer, flagship only)
   * `chappM/whisper-amharic-small-v2` (OpenRAIL, CV17 WER 69 reported) is usable commercially
     under OpenRAIL use restrictions; review before shipping.
   Published research (arXiv:2503.18485) reaches ~31 % WER on FLEURS with a fine-tuned
   whisper-small, which is the realistic target quality.
2. Run `python tools/convert_whisper_to_ggml.py --repo <id> --size small --quantize q5_1`.
3. Host the `.bin` on your own Hugging Face repo (or any HTTPS host) and paste `sizeBytes` and
   `sha256` into `ModelCatalog.whisperAmharicSmallQ5` (or ship them through a remote catalog JSON
   merged with `ModelManager.mergeCatalog`). The entry becomes visible automatically.
4. Run the AI quality harness (`tests/ai-quality/README.md`) on consented Amharic recordings and
   record WER/CER/RTF in `docs/TEST_RESULTS.md` before removing the "experimental" flag.

### Known deviations

* Decoding is greedy with temperature fallback (`temperature_inc = 0.2`); beam search is off for
  latency.
* Partial/streaming transcripts are not implemented (whisper.cpp needs full segments). The UI
  never shows fabricated partial text.
* Whisper's X→English `translate` flag is available in `TranscribeOptions` but not exposed in the
  UI because its Amharic quality is unverified.

## Other speech models evaluated on paper (2026-10-12, not integrated)

| Model | License | Verdict |
|---|---|---|
| Meta Omnilingual ASR (`facebook/omniASR-CTC-300M` … 7B, wav2vec2-CTC and LLM variants, 1,600+ languages) | Apache-2.0 (weights + code) | **Best next candidate.** 300M CTC is ~325M params (1.3 GB fp32 → ~330 MB int8); CTC decoding cannot loop/hallucinate like Whisper; covers Tigrinya and Afaan Oromoo, which Whisper lacks. Needs fairseq2 → ONNX export and on-device measurement before use. |
| Google Chirp 3 (Cloud Speech-to-Text v2) | Proprietary cloud API, billed per minute | Not usable as default: online only and recurring cost, conflicting with the offline/no-paid-API requirements. Could become an optional, consent-gated cloud fallback. |
| Meta SeamlessM4T v2 | CC-BY-NC-4.0 | Rejected: non-commercial; 2.3B params; Amharic is a source language only. |

## Translation models (ONNX Runtime, int8)

| Model | Download | RAM | Tier | Directions enabled | Expected quality (literature) |
|---|---|---|---|---|---|
| m2m100-418m-int8 (default) | 632 MB (5 files) | ~1.1 GB | MID | am⇄en, so⇄en, ar⇄en, am⇄ar | am→en BLEU mid-20s to ~37 on Tatoeba-type data; en→am roughly 10–15 BLEU (weak) |
| opus-mt-mul-en-int8 | 115 MB | ~350 MB | LOW | am/ti/so/ar → en | am→en Tatoeba BLEU 25.6 / chrF 0.46; ti→en 9.1 (poor) |
| opus-mt-en-mul-int8 | 115 MB | ~350 MB | LOW | en → am/ti/so/ar | en→am Tatoeba BLEU 8.5 (basic); en→ti 1.5 (near unusable; still listed because the model emits Tigrinya, flagged "basic") |

Status: tokenizer verified against the reference implementation (44/44 fixture cases); ONNX
graph execution follows the Optimum merged-decoder contract (`use_cache_branch`, empty past
tensors on step 0). **End-to-end inference on a device has not been run yet** (no device or
emulator was available in the build environment); this is the first item in the device
validation checklist.

Tokenizer deviation: the `nmt_nfkc` precompiled charsmap is not replayed; we apply NFKC +
control-character removal + whitespace unification, which produced identical pieces for every
Ge'ez, Latin and Arabic fixture.

## Text-to-speech

* Android system `TextToSpeech`. Google's engine offers an Amharic (am-ET) voice that can be
  downloaded for offline use; availability is checked at runtime with `isLanguageAvailable`.
  The app never claims a voice exists until that check passes.
* No commercially licensed offline Amharic TTS model exists today (all candidates are
  CC-BY-NC), so none is bundled. See `docs/LICENSES.md`.

## Device validation checklist (requires hardware)

1. Install debug APK on a low-end (3 GB), mid-range (6 GB) and flagship phone.
2. Download whisper-base-q5_1; dictate English; record inference ms / RTF from the result card.
3. Convert + install an Amharic fine-tune; dictate the `tests/ai-quality` prompt list; compute WER/CER.
4. Download m2m100-418m-int8; translate the fixture sentences; record latency and peak memory
   (`adb shell dumpsys meminfo`).
5. Keyboard: enable, select, dictate into WhatsApp/Telegram/Gmail/Chrome; verify insertion,
   password-field blocking, rotation, app switch, IME recreation (change system theme).
6. Permission denial, microphone busy (start a call), empty recording, airplane-mode download.
7. Run `./gradlew connectedDebugAndroidTest` for `RoomPersistenceTest`.
