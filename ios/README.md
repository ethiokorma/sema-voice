# Sema Voice — iOS (Phase 2 skeleton)

Not compiled yet: this folder was authored on Windows without Xcode. It contains the
legally compliant design (no microphone in the keyboard extension) and Swift sources that
mirror the Android engine contracts.

```
Package.swift                         SwiftPM package "SemaVoiceCore"
Sources/SemaVoiceCore/Engines.swift   Language, AppError, PcmAudio, engine protocols, DictationHandoff (App Group)
Sources/SemaVoiceCore/ModelCatalog.swift  same URLs/SHA-256 as the Kotlin catalog
Tests/SemaVoiceCoreTests/             XCTest for catalog + hand-off round trip
SemaVoiceApp/SemaVoiceApp.swift       SwiftUI app: AVAudioEngine 16 kHz capture -> SpeechRecognitionEngine -> App Group hand-off
SemaKeyboard/KeyboardViewController.swift  UIInputViewController: "Dictate in Sema Voice" (URL scheme) + "Insert last dictation"
```

Next steps on a Mac: create an Xcode project with an App target, a Custom Keyboard Extension
target, an App Group `group.com.semavoice.app`, the URL scheme `semavoice`, add whisper.cpp's
Swift package (tag v1.9.5) and implement `SpeechRecognitionEngine` with it. See
`../docs/IOS_PLAN.md`.
