# ARCHITECTURE

Sema Voice is a modular Android application (Kotlin, Jetpack Compose, Coroutines/Flow) with a
native speech engine. The iOS and desktop targets reuse the same engine contracts (see
`docs/IOS_PLAN.md`).

```
app/                Compose app: navigation, screens, view models, resources (en + am)
keyboard/           InputMethodService + Compose keyboard UI + KeyboardController
core/common/        Language, AppError, Outcome, DispatcherProvider, TextNormalizer, PcmAudio
core/audio/         AudioRecorder (AudioRecord), RecordingStateMachine, VAD, Resampler, WavCodec
core/speech/        SpeechRecognitionEngine, WhisperEngine, WhisperNative (JNI), cpp/ (CMake + whisper.cpp)
core/translation/   TranslationEngine, OnnxSeq2SeqTranslator, SentencePieceModel/Tokenizer
core/writing/       WritingAssistantEngine, RuleBasedWritingAssistant, DictionaryPostProcessor
core/models/        ModelCatalog (checksums), ModelDownloader, ModelManager, DeviceCompatibility
core/database/      Room (notes, history, dictionary), CryptoBox (Keystore AES-GCM), repositories
core/settings/      DataStore-backed SettingsRepository (every preference in section 18)
core/tts/           TtsEngine + AndroidSystemTts
core/runtime/       SemaGraph (object graph), DictationPipeline (orchestration), VoiceStudioClient
tools/              convert_whisper_to_ggml.py, gen_spm_fixtures.py
scripts/            fetch-whisper-cpp.{sh,ps1}
ios/                Swift package skeleton + plan (not buildable on Windows)
docs/               this folder
```

## Dependency direction

`app` → `keyboard` → `core/runtime` → every `core/*`. `core/*` modules depend only on
`core/common` (and `core/translation` on `core/models` for catalog metadata). The keyboard and
the app run in the same process and share one `SemaGraph`, so a model loaded by the IME is
reused by the app and vice versa.

## Key flows

### Dictation (keyboard or app)

1. `AudioRecorder.start()` opens `AudioRecord` at 16 kHz mono (falls back to 48/44.1 kHz +
   `Resampler`). Every 30 ms frame passes through `VoiceActivityDetector` for the level meter,
   silence auto-stop and later silence trimming. State transitions are validated by the pure
   `RecordingStateMachine` (unit-tested), so illegal events never crash the IME.
2. `DictationPipeline.process()`:
   * picks the best installed speech model for the language (`ModelManager.bestSpeechModelFor`),
     loads it through `WhisperEngine.initialize` (one model resident at a time),
   * runs `WhisperNative.nativeTranscribe` on the single-threaded inference dispatcher with the
     user dictionary as `initial_prompt`,
   * applies `DictionaryPostProcessor` (exact variants auto-applied, fuzzy matches only suggested),
   * applies the dictation mode (`DictationModes`: EXACT = normalize only; CLEAN = fillers +
     punctuation; TRANSLATE = CLEAN + translation),
   * optionally translates through `OnnxSeq2SeqTranslator`,
   * writes history only when `saveHistory` is on.
3. The keyboard shows the result for review; `KeyboardController.insert()` commits through
   `InputConnection.commitText` with context-aware spacing (`FieldRules.joinWithContext`).
   Secure fields are refused (`FieldRules.isSecure`).

### Native speech

`core/speech/src/main/cpp/CMakeLists.txt` adds whisper.cpp's `ggml` as a static subproject and
compiles `whisper.cpp` + `sema_whisper_jni.cpp` into `libsema_whisper.so` (arm64-v8a, x86_64).
arm64 is built for ARMv8.2-A+fp16+dotprod; `WhisperNative.deviceSupportsNativeLib()` checks
`/proc/cpuinfo` for `fphp`/`asimddp` before `System.loadLibrary` so older cores get an
`UnsupportedDevice` error instead of SIGILL. Linker flags enforce 16 KB page alignment.

The JNI bridge wraps a `whisper_context` in a struct with an atomic abort flag (cancel) and an
atomic progress value (polled by Kotlin). Segment text is returned via `String(bytes, UTF_8)`
to avoid modified-UTF-8 issues.

### Translation

Optimum-style ONNX exports (`encoder_model_quantized.onnx`, `decoder_model_merged_quantized.onnx`).
`SentencePieceModel` parses the protobuf model; `SentencePieceTokenizer` implements unigram
Viterbi (Marian) and score-ranked BPE merges (M2M100), verified against the reference Python
implementation by `SentencePieceTokenizerTest` (44 fixture cases). Pieces map to ids through
`vocab.json` (not the ids inside the .spm). Decoding is greedy with a KV cache, a
no-repeat-4-gram guard and per-family special-token handling (M2M100: `</s>` + forced target
language token; Marian: `<pad>` start, `>>xxx<<` target prefix).

### Model manager

`ModelCatalog` is the single source of truth (URL, size, SHA-256, languages, RAM, tier, license).
`ModelDownloader` resumes with HTTP Range, verifies SHA-256, and deletes mismatches.
`ModelManager` keeps per-model `InstallState` in a `StateFlow`, performs pre-flight checks
(network, Wi-Fi-only, storage, RAM budget, ABI/CPU), and exposes `installed.json` manifests so
app updates do not re-download. Entries without a verified checksum (the Amharic fine-tunes
until converted and hosted) are hidden (`ModelCatalog.isPublishable`).

### Writing assistant

`RuleBasedWritingAssistant` implements only deterministic transformations (punctuation,
fillers, repeated words, common spelling fixes, redundancy removal). Style rewrites report
`Availability.UNAVAILABLE` and the UI disables them; no mock LLM output anywhere.

### Privacy boundaries

See `docs/PRIVACY.md`. The only outbound network calls are `ModelDownloader` (model files) and
`VoiceStudioClient` (consent-gated).

## Threading

* Native inference: `DispatcherProvider.inference` = `Dispatchers.Default.limitedParallelism(1)`.
* Audio reads: `Dispatchers.IO` coroutine inside `AudioRecorder`.
* UI: `StateFlow` collected in Compose; the IME hosts Compose with its own
  `LifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner` (`SemaInputMethodService`).

## Testing layers

* Unit (JVM): common, audio (state machine, VAD, resampler, WAV), models (catalog, compatibility,
  SHA-256), translation (tokenizer vs reference fixtures), writing (rules, dictionary), keyboard
  (field rules / insertion spacing).
* Instrumentation: `RoomPersistenceTest` (encryption at rest, search, audio deletion, filters,
  dictionary import/export). Requires a device/emulator.
* AI quality: `tests/ai-quality/` harness (see `docs/TEST_RESULTS.md`).
