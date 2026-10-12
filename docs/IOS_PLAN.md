# iOS implementation plan (Phase 2)

Status: **planned, source skeleton only.** The Swift sources under `ios/` were written on a
Windows machine without Xcode and have not been compiled. Everything below is designed to be
legally and technically compliant with iOS; nothing pretends the keyboard can record audio.

## Hard platform constraints

1. **Custom keyboard extensions cannot access the microphone.** Apple does not grant
   `NSMicrophoneUsageDescription` to keyboard extensions and `AVAudioSession` recording fails
   there. Therefore the Sema iOS keyboard never shows a working microphone button of its own.
2. Keyboard extensions get ~70 MB of memory and no network unless "Full Access" is granted.
   whisper.cpp with a quantized base model (~60 MB file + working memory) does **not** fit
   reliably; inference must happen in the containing app.
3. App Groups allow the app and its extensions to share files and `UserDefaults`.

## Compliant recording flow

```
Keyboard extension                          Containing app (Sema Voice)
------------------                          --------------------------
[Mic button] --openURL(semavoice://dictate)--> Dictation sheet opens (AVAudioEngine 16 kHz)
                                              whisper.cpp (Swift package, Metal/CPU) transcribes
                                              result written to App Group container + pasteboard
<-- user returns (back-swipe / "Back to app") --
[Insert last dictation] reads App Group file -> textDocumentProxy.insertText(result)
```

* Alternative one-tap path: the app's dictation result is also copied to the general pasteboard
  (opt-in, user is told) so any keyboard can paste it.
* Siri Shortcuts / App Intents (`DictateIntent`, `TranslateIntent`) expose "Transcribe my voice"
  and "Translate text" for the Shortcuts app and Action Button.
* Share Extension: receive text from any app → translate → copy/share.

## Shared engine contracts

The Kotlin interfaces map 1:1 to Swift protocols in `ios/Sources/SemaVoiceCore/Engines.swift`:

| Kotlin | Swift |
|---|---|
| `SpeechRecognitionEngine` | `protocol SpeechRecognitionEngine` |
| `TranslationEngine` | `protocol TranslationEngine` |
| `WritingAssistantEngine` | `protocol WritingAssistantEngine` |
| `TtsEngine` | `protocol TtsEngine` (backed by `AVSpeechSynthesizer`, which has an Amharic voice on iOS 16+ when the user downloads it) |
| `ModelCatalog` (same URLs + SHA-256) | `ModelCatalog` (generated from the Kotlin file to stay in sync) |

Implementations planned:

* Speech: whisper.cpp via its official Swift package (`ggml-org/whisper.cpp` → `whisper.xcframework`),
  CoreML encoder optional.
* Translation: `onnxruntime-objc` pod / SwiftPM binary (MIT) with the same int8 ONNX files and a
  Swift port of `SentencePieceTokenizer` (the Kotlin version is small and has fixture tests that
  can be reused verbatim).
* Storage: SwiftData/CoreData with `NSFileProtectionComplete`; Keychain-backed key for
  transcript encryption to mirror `CryptoBox`.

## Milestones

1. SwiftPM package `SemaVoiceCore` compiles on macOS/iOS with protocol definitions + model catalog.
2. Containing app: onboarding, dictation sheet, model manager (URLSession background downloads with
   SHA-256), translate screen.
3. Keyboard extension: QWERTY + Ethiopic layout, "Dictate in Sema" button (deep link), "Insert
   last dictation" button, translation toggle reading App Group data.
4. App Intents + Share Extension.
5. TestFlight device validation on iPhone 11 (A13) through iPhone 16; measure RTF and memory.

## What cannot be promised

* Real-time dictation inside third-party apps without leaving them (platform limitation).
* Offline Amharic TTS beyond what Apple's system voices provide.
