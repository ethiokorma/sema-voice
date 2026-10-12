# TEST_RESULTS

Honest status of every verification step. Updated 2026-10-12.
Build host: Windows 10 Pro, Temurin JDK 17.0.20, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1,
Gradle 8.11.1, AGP 8.10.1, Kotlin 2.1.21. **No Android device or emulator was attached**, so
everything marked "device" below is pending physical validation.

## Build

| Step | Result |
|---|---|
| `./gradlew :app:assembleDebug` | PASS — `app/build/outputs/apk/debug/app-debug.apk` (≈125 MB debug build: unstripped native libs + arm64 and x86_64 ABIs) |
| Native build `libsema_whisper.so` (whisper.cpp v1.9.5 + ggml, arm64-v8a + x86_64) | PASS — compiled with NDK r27 clang, `llvm-readelf -l` shows LOAD alignment 0x4000 (16 KB pages) |
| `./gradlew :app:assembleRelease` (R8, resource shrinking, native libs stripped) | PASS — `app/build/outputs/apk/release/app-release-unsigned.apk` (≈83 MB; most of it is ONNX Runtime + whisper for two ABIs — publish as an App Bundle so Play serves one ABI, ≈45 MB per device) |
| `./gradlew :app:assembleDebugAndroidTest` | PASS — `app-debug-androidTest.apk` (1 MB) |
| Final clean run `testDebugUnitTest :app:assembleDebug :app:assembleRelease` | BUILD SUCCESSFUL in 14m 40s, 67/67 tests passed |

## Unit tests (JVM, `./gradlew testDebugUnitTest`)

| Module | Tests | Result | What they cover |
|---|---|---|---|
| core:common | 11 | PASS | Language table (Tigrinya/Oromo not Whisper languages), Outcome, text normalization, Ethiopic punctuation, mixed-script detection |
| core:audio | 13 | PASS | Recording state machine (happy path, pause/resume, empty recording → failure, cancel, illegal transitions ignored, recovery), VAD, silence trimming, resampler, WAV round trip |
| core:models | 14 | PASS | Catalog ids/checksums, Amharic entries hidden until checksums exist, stock Whisper does not claim Amharic, direction/language consistency, SHA-256 vector, compatibility rules (RAM budget, tier, ARMv8.2, ABI), remote catalog parsing |
| core:translation | 4 | PASS | SentencePiece protobuf parsing (BPE 128k / unigram 32k), **44/44 encodings identical to the reference Python `sentencepiece` implementation** (Amharic, English, mixed, Arabic, Somali), decode round trip, empty input |
| core:writing | 14 | PASS | Honest availability (style rewrites UNAVAILABLE), fillers (en + am), repeated words incl. Amharic reduplication, punctuation (Latin + Ethiopic), spelling, shortening, dictionary auto-corrections vs suggestions, no cross-script fuzzy matches, Levenshtein budget |
| keyboard | 11 | PASS | Secure-field detection (password/PIN/web), numeric fields, no-field detection, insertion spacing (Latin + Ge'ez), Ge'ez phonetic composer (ሰላም, አማርኛ, digraphs, standalone vowels) |

Total: 67 unit tests (the exact count after the final run is in the console log; see
`*/build/test-results/testDebugUnitTest/*.xml`).

## Instrumentation tests (device required)

`core/database/src/androidTest/.../RoomPersistenceTest.kt` — 5 tests: encrypted-at-rest round trip
(plaintext absent from the DB row), Amharic search, delete-audio-keep-transcript, history filters,
dictionary import/export. **Compiled (`assembleDebugAndroidTest`) but NOT executed** — no device.

Not yet written (need a device to be meaningful): IME enablement, keyboard input in real apps,
permission flow, background/foreground transitions, IME recreation, model download. These are in
the manual device checklist in `docs/MODEL_COMPATIBILITY.md`.

## AI quality — Amharic model evaluation (host CPU, whisper.cpp v1.9.5 via pywhispercpp, 4 threads)

Dataset: first 30 utterances of FLEURS `am_et` test split (CC-BY-4.0), 16 kHz mono, reference
transcripts from the dataset. Per-utterance details: `docs/ai-quality-*.json`.

| Model file (SHA-256 in `dist/models/catalog.json`) | Source | WER | CER | mean RTF (host) | Verdict |
|---|---|---|---|---|---|
| ggml-tiny-q5_1.bin (stock, 31 MB) | ggerganov/whisper.cpp | **1.287** | 1.663 | 0.67 | Unusable for Amharic (confirms the Whisper paper) |
| ggml-tiny-amharic.bin (f16, 74 MB) | b1n1yam/shook-tiny-amharic-600hr, Apache-2.0 | **0.813** | 0.609 | 3.16 | Partially usable: several clips reach WER 0.18 / CER 0.03, others hallucinate in loops (RTF 6–9). Not good enough to ship as default |
| ggml-tiny-amharic-q5_1.bin (31 MB) | same, quantized | not measured (time); expected within a few points of f16 | | | Shipped as the recommended Amharic entry in `dist/models/catalog.json` |
| ggml-small-amharic-q5_1.bin (181 MB) | seyyaw/whisper-finetuned-amharic, Apache-2.0 | 1.178 | 1.263 | 7.85 (median 7.25) | **REJECTED**: output is Hebrew/Latin garbage (e.g. "בסשיטуч בודןויסט…", "switch"); 0/30 clips usable |
| ggml-small-amharic.bin (f16, 465 MB) | same | 1.180 | 1.239 | 5.75 | REJECTED (same behaviour) |

Observations:
* The tiny fine-tune's first FLEURS clip transcribes almost perfectly ("በሴቶች ቡድን ውስጥ አራት የበረዶ
  ተንሸራታቾች ሩጫቸዋን መጨረስ አልቻሉም…" vs reference "…ሩጫቸውን…"); its errors are dominated by decoder
  repetition loops at segment ends (6/30 clips have WER > 1.0 purely from inserted repeats), which
  also inflate RTF. A naive exact-repeat collapser did not help (repeats vary slightly), so no
  post-processing was added; mitigation candidates are whisper.cpp `max_tokens`, stricter
  `entropy_thold`, or a better fine-tune (research reports ~31 % WER for a well-trained small).
* English sanity check (Windows SAPI voice → 16 kHz → stock ggml-tiny-q5_1): "The Quick Brown Fox
  jumps over the lazy dog. Seem a voice is an M-Harex speech recognition app for Android." —
  i.e. real inference through the same ggml file the app downloads; proper nouns misheard as expected
  for Tiny.
* Conversion + quantization (`tools/convert_whisper_to_ggml.py` flow; whisper.cpp
  `whisper-quantize` built natively with MinGW) succeeded for both checkpoints. Files and checksums
  are in `dist/models/` (binaries git-ignored, `catalog.json` committed and already pruned to the
  working tiny model). Host CPU: see `wmic cpu get name` on the build machine; RTF on phones will differ.

Phone RTF/memory numbers are pending device validation.

## PWA (`web/`)

| Step | Result |
|---|---|
| App shell (index.html, app.js, sw.js, manifest) | written; English + Amharic UI strings; not yet opened in a browser |
| Amharic model export to ONNX int8 for transformers.js | PASS — 9 MB encoder + 47 MB merged decoder in `web/models/whisper-tiny-amharic/onnx/` |
| Exported graph produces Amharic (ONNX Runtime, Python) | PASS — FLEURS clips 000 and 002 transcribed near reference; 12.8 s for 14.8 s audio on host CPU |
| transformers.js end-to-end (Node harness `web/test/transcribe-node.mjs`) | BLOCKED on build machine — onnxruntime-node native binary crashes (0xC06D007F); browser path (ORT-web WASM) still to be confirmed manually |

## Acceptance criteria (section 24)

| # | Criterion | Status |
|---|---|---|
| 1 | App builds | PASS |
| 2 | Installable on a supported device | PENDING (no device) — APK produced, minSdk 26, arm64-v8a/x86_64 |
| 3 | User can enable the keyboard | IMPLEMENTED (IME declared, onboarding + Home deep-link to system settings) — device pending |
| 4 | Microphone permission | IMPLEMENTED (in-context request + rationale) — device pending |
| 5 | Records real speech | IMPLEMENTED (AudioRecord 16 kHz, VAD, state machine unit-tested) — device pending |
| 6 | whisper.cpp real local inference | IMPLEMENTED + native lib compiled; inference exercised on host CPU via the same ggml files (see evaluation) — device pending |
| 7 | Amharic speech → text | PARTIAL — real Amharic text produced by the converted tiny fine-tune through whisper.cpp on host (WER 0.81, repetition loops); the file must be hosted by the publisher and validated on a phone |
| 8 | Inspect and correct text | IMPLEMENTED (editable review in keyboard and app) |
| 9 | Insert into active field | IMPLEMENTED (`InputConnection.commitText`, spacing rules unit-tested) — device pending |
| 10 | English translation through a real integrated model | IMPLEMENTED (ONNX Runtime, M2M100/OPUS-MT, tokenizer verified) — end-to-end ONNX run pending device |
| 11 | Works offline after setup | DESIGNED (no network code outside downloader + consent-gated Voice Studio) — device pending |
| 12 | Models download/delete | IMPLEMENTED (resume, SHA-256, Wi-Fi-only, storage/RAM pre-flight) — device pending |
| 13 | Save/delete voice notes | IMPLEMENTED (Room, encrypted, foreground service) — Room test compiled, device pending |
| 14 | Recording stops on cancel/lifecycle | IMPLEMENTED (`onFinishInputView`/`onDestroy` → cancel/release; ViewModel `onCleared`) — device pending |
| 15 | Handles failures without crashing | IMPLEMENTED (every path returns `AppError`; CPU-feature check prevents SIGILL) — device pending |
| 16 | Accessibility + localization | IMPLEMENTED (en + am strings, content descriptions, large-target and high-contrast settings, system font scaling) — TalkBack pass pending |
| 17 | No paid API key | PASS |
| 18 | Model licenses documented | PASS (`docs/LICENSES.md`) |
| 19 | Automated tests pass | PASS (unit); instrumentation pending device |
| 20 | Debug APK / build instructions | PASS (`README.md`) |
