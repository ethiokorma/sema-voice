# Sema Voice

**Speak in your language. Write in any language.**

Offline-first multilingual voice typing, translation and writing assistant for Android, with
Amharic and English first. Speech recognition (whisper.cpp) and translation (ONNX Runtime +
M2M100 / OPUS-MT) run entirely on the phone. No account, no paid API, no ads, no tracking.

* System keyboard (InputMethodService) with a large microphone, exact / clean / translate modes,
  review-before-insert, English QWERTY fallback and Ethiopic punctuation.
* Full app: Home, Voice Typing, Translate, Voice Notes, History, Settings, Model manager,
  Personal dictionary, optional Voice Studio (system TTS + user-run server).
* Models are downloaded on demand, SHA-256 verified, and deletable. The app works with no
  internet after setup.
* UI in English and Amharic; dark mode; large-target and high-contrast options.
* **PWA** (`web/`): the same dictation + translation in any browser via transformers.js, offline
  after first load, installable; it cannot act as a system keyboard. See `web/README.md`.

Read `docs/ARCHITECTURE.md`, `docs/MODEL_COMPATIBILITY.md`, `docs/LICENSES.md`,
`docs/PRIVACY.md`, `docs/TEST_RESULTS.md` and `docs/IOS_PLAN.md`.

## Important honesty notes

* **Amharic needs a fine-tuned Whisper model.** Stock Whisper is unusable for Amharic (WER > 100 %).
  The catalog ships hidden Amharic entries; run `tools/convert_whisper_to_ggml.py` on a
  commercially licensed fine-tune, host the file, and fill in the checksum. See
  `docs/MODEL_COMPATIBILITY.md`.
* The build in this repository was compiled and unit-tested on a machine **without an Android
  device or emulator**. On-device validation items are listed in `docs/TEST_RESULTS.md`.
* iOS is a planned phase; `ios/` contains an uncompiled Swift skeleton and the compliant design.

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35.0.0), NDK 27.2.12479018,
CMake 3.22.1. The Gradle wrapper downloads Gradle 8.11.1.

```bash
# 1. Fetch whisper.cpp v1.9.5 sources (MIT) into core/speech/src/main/cpp/whisper.cpp
./scripts/fetch-whisper-cpp.sh          # or: powershell scripts/fetch-whisper-cpp.ps1

# 2. Point Gradle at your SDK (local.properties)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 3. Debug APK
./gradlew assembleDebug
#    -> app/build/outputs/apk/debug/app-debug.apk

# 4. Unit tests (JVM)
./gradlew testDebugUnitTest

# 5. Instrumentation tests (device or emulator required)
./gradlew connectedDebugAndroidTest
```

Install: `adb install app/build/outputs/apk/debug/app-debug.apk`, open Sema Voice, follow the
7-step onboarding (languages → model download → enable keyboard → microphone → try it).

### Release build

```bash
./gradlew assembleRelease   # add signingConfigs in app/build.gradle.kts or sign with apksigner
```

R8 is enabled; JNI and ONNX Runtime classes are kept by `app/proguard-rules.pro`. Native
libraries are 16 KB page aligned (NDK r27 + explicit linker flags).

## Repository layout

See `docs/ARCHITECTURE.md`. Short version: `app/` (Compose UI), `keyboard/` (IME),
`core/*` (audio, speech, translation, writing, models, database, settings, tts, runtime),
`tools/` (model conversion and fixture scripts), `tests/ai-quality/` (WER/CER harness),
`ios/` (Phase 2 skeleton).

## Models

| Model | Size | License | Use |
|---|---|---|---|
| Whisper base q5_1 (default) | 57 MB | MIT | English / Arabic / Somali dictation |
| Whisper tiny/small/medium/large-v3-turbo q5 | 31 MB – 547 MB | MIT | larger/faster variants |
| Whisper small Amharic fine-tune (convert + host yourself) | ~190 MB | Apache-2.0 | Amharic dictation |
| M2M100 418M int8 | 632 MB | MIT | Amharic ⇄ English (+ Somali, Arabic) |
| OPUS-MT mul→en / en→mul int8 | 115 MB each | Apache-2.0 | lightweight translation |

All checksums are pinned in `core/models/src/main/kotlin/com/semavoice/core/models/ModelCatalog.kt`.
