# LICENSES

Every third-party component and AI model used by Sema Voice, with the license that was
inspected on 2026-10-12. Nothing in this list is non-commercial. Models that failed the
license check are listed at the bottom so nobody re-adds them by accident.

## Code dependencies

| Component | Version | License | Use |
|---|---|---|---|
| whisper.cpp + ggml | v1.9.5 (fetched by `scripts/fetch-whisper-cpp.*`) | MIT | On-device speech recognition (`core/speech`, JNI) |
| ONNX Runtime (onnxruntime-android) | 1.31.0 | MIT | On-device translation inference (`core/translation`) |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | 2.1.21 / 1.10.2 / 1.8.1 | Apache-2.0 | Language and runtime |
| AndroidX (Compose, Room, DataStore, Navigation, Lifecycle, Core, SavedState, SplashScreen) | see `gradle/libs.versions.toml` | Apache-2.0 | UI, persistence, navigation |
| Material 3 Compose + Material Icons Extended | Compose BOM 2025.05.01 | Apache-2.0 | UI components / icons |
| OkHttp | 4.12.0 | Apache-2.0 | Model downloads, optional Voice Studio client |
| JUnit 4, AndroidX Test | 4.13.2 / 1.6.x | EPL-1.0 / Apache-2.0 | Tests only (not shipped) |
| SentencePiece model format | n/a | Apache-2.0 (Google) | We parse `.model` / `.spm` files with our own Kotlin reader; no SentencePiece code is linked |

Attribution requirements: MIT and Apache-2.0 require the license text to be reproduced in the
product. The About screen (`AboutScreen`) lists every component above and the model credits.

## AI models (downloaded at runtime, never bundled in the APK)

| Model | Source | Weights license | Commercial use | Attribution |
|---|---|---|---|---|
| Whisper tiny/base/small/medium/large-v3-turbo (ggml, quantized) | `ggerganov/whisper.cpp` on Hugging Face | MIT (OpenAI Whisper) | Yes | OpenAI Whisper; ggml conversion by ggml.ai |
| M2M100 418M int8 ONNX | `Xenova/m2m100_418M` (export of `facebook/m2m100_418M`) | MIT | Yes | Fan et al. 2020, Meta AI; ONNX export by Xenova |
| OPUS-MT mul→en int8 ONNX | `Xenova/opus-mt-mul-en` (export of `Helsinki-NLP/opus-mt-mul-en`) | Apache-2.0 (HF card); OPUS-MT project states CC-BY-4.0 | Yes (attribution) | Tiedemann & Thottingal 2020, Helsinki-NLP |
| OPUS-MT en→mul int8 ONNX | `Xenova/opus-mt-en-mul` | Apache-2.0 / CC-BY-4.0 | Yes (attribution) | Helsinki-NLP |
| Amharic Whisper fine-tune, converted to ggml (`dist/models/ggml-tiny-amharic*.bin`) | `b1n1yam/shook-tiny-amharic-600hr` | Apache-2.0 (model card) | Yes; training-data provenance is not documented by the author, so the app labels it experimental | b1n1yam + OpenAI Whisper |
| **Ethio-ASR Amharic CTC (int8 ONNX)**, release asset `ethio-asr-ctc-*` | `badrex/Ethio-ASR-amharic` (w2v-BERT 2.0, 600M) | **CC-BY-4.0** | Yes, with attribution. Training data is WAXAL Amharic: Google's WaxalNLP card says CC-BY-SA-4.0 while the model card says CC-BY-4.0. Have a lawyer confirm before a paid release. | "Ethio-ASR, Badr M. Abdullah et al., arXiv:2603.23654; trained on WAXAL (Google)" plus the "modified: converted to ONNX, int8" note. Shown in the model manager and About screen. |
| Hohe ASR Amharic (evaluated, not shipped) | `snapwre/hohe-asr-amharic` | CC-BY-4.0 | Yes; the training corpus is undisclosed | snapwre |
| Other Apache-2.0 Amharic fine-tunes not yet converted | `mintesnotfikir/whisper-medium-amharic` | Apache-2.0 | Yes (unmeasured) | author + OpenAI Whisper |
| `BuzzASR/amharic` (large-v3 fine-tune) | Hugging Face | MIT | Yes; too large for most phones, custom tokenizer | BuzzASR |
| Android system text-to-speech voices | Google "Speech Recognition & Synthesis" (on device) | Google Play Services terms; used only via the public `TextToSpeech` API | Yes | n/a |

Checksums for every downloadable file are pinned in `core/models/.../ModelCatalog.kt` and
verified before a file is ever loaded.

## Evaluated and rejected (do not add)

| Model / project | Reason |
|---|---|
| facebook/nllb-200-distilled-600M, -1.3B and all NLLB fine-tunes (e.g. `dsfsi/nllb_200_distilled_600m-amh-eng`) | CC-BY-NC-4.0 |
| `masakhane/m2m100_418M_amh_en_rel` | CC-BY-NC-4.0 (MAFAND data) |
| facebook/mms-tts-amh and every derivative (`hadamard-2/mms-tts-amh-onnx`) | CC-BY-NC-4.0 |
| k2-fsa/OmniVoice and its Amharic fine-tunes (`Lab-et/omnivoice-amharic`, `gheero-Leyu/amharic-omnivoice-tts`) | Base weights CC-BY-NC; Apache tags on fine-tunes are not valid for commercial use |
| Coqui XTTS-v2 | Coqui Public Model License (non-commercial) |
| Fish Speech / OpenAudio S1 | CC-BY-NC-SA-4.0 |
| F5-TTS weights | CC-BY-NC-4.0 |
| SeamlessM4T v2 | CC-BY-NC-4.0 |
| OHF-Voice/piper1-gpl | GPL-3.0 (cannot be linked into a proprietary app) |
| Piper `sw_CD-lanfrica` voice | Dataset restricted to non-profit use |
| debpalash/VoiceStudio | AGPL-3.0 and default OmniVoice weights are CC-BY-NC. Sema Voice does **not** link or ship any of its code; the optional integration talks to a user-run instance over HTTP only (see `VoiceStudioClient`). |
| `seyyaw/whisper-finetuned-amharic` | License is fine (Apache-2.0) but the converted model outputs Hebrew/Latin garbage for Amharic audio (measured, see TEST_RESULTS) |
| facebook/mms-1b-all, mms-300m; waxal-benchmarking/mms-300m-waxal-amh, omniasr-ctc-300m-waxal-amh, omniasr-llm-300m-waxal-amh; badrex/Ethio-ASR-multilingual-94M / -300M; misterkissi/w2v2-lg-xls-r-1b-amharic | CC-BY-NC or CC-BY-NC-SA (non-commercial) |
| Amharic checkpoints with no licence stated (b1n1yam/shook-medium-amharic-2k, Bmancman/Bell-whisper-large-v3-turbo-amharic, ethiospeech01/*, KYAGABA/*, Gizachew/*) | No licence means no right to redistribute |
| CTranslate2 | MIT, but no official Android build |
| Google ML Kit Translation | Closed source and no Amharic support |

`msrbuilds/voice-studio` is MIT and is supported as an optional remote server through its
documented `POST /api/synthesize` endpoint; none of its code is included.
