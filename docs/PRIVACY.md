# PRIVACY

Sema Voice is offline-first. This document states what the app does with data and where in
the code each guarantee is enforced, so it can be audited rather than trusted.

## Summary

| Guarantee | Enforced by |
|---|---|
| No account, no sign-up | There is no auth code path at all |
| No analytics / tracking / ads SDK | No such dependency in `gradle/libs.versions.toml` |
| Microphone only on explicit user action | `AudioRecorder.start()` is called only from the mic button handlers (`KeyboardController.startRecording`, `DictationViewModel.start`, `NotesViewModel.startRecording`). There is no background recording. |
| Visible recording indicator | Keyboard shows a red state + level bars; app screens show "Listening…"; voice notes run a foreground service with a persistent notification (`NoteRecordingService`) |
| Audio never uploaded | Speech runs in-process through `WhisperNative` (JNI). The only network client with audio capability is `VoiceStudioClient.transcribe`, which refuses unless `voiceStudioEnabled && voiceStudioConsentGiven` are both true in settings |
| Translation runs locally | `OnnxSeq2SeqTranslator` loads files from `filesDir/models` and never opens a socket |
| No keystroke logging | `KeyboardController.typeKey` writes directly to `InputConnection.commitText`; nothing is stored. `SemaLog` is documented as content-free and only receives lifecycle messages |
| Voice input disabled in password fields | `FieldRules.isSecure` (unit-tested) blocks recording and insertion for password/PIN/web-password input types |
| Local storage encrypted | `CryptoBox`: AES-256-GCM with a non-exportable Android Keystore key; transcripts and translations are stored as ciphertext (`NoteEntity.transcriptEnc`, `HistoryEntity.originalEnc`). Instrumentation test `RoomPersistenceTest.noteRoundTripIsEncryptedAtRest` verifies plaintext is absent from the DB row |
| History is opt-in | `SemaSettings.saveHistory` defaults to `false`; `DictationPipeline` only writes history when it is true |
| Audio retention is a choice | `AudioRetention.ASK` default; "Delete audio, keep transcript" implemented in `NotesRepository.deleteAudioOnly` |
| Delete everything | Settings → Clear all data: wipes notes, history, dictionary, preferences and destroys the Keystore key (`CryptoBox.destroyKey`) |
| No backups to the cloud | `android:allowBackup="false"` and `data_extraction_rules.xml` exclude all domains |
| Model downloads are verified | `ModelDownloader` checks SHA-256 against `ModelCatalog` before renaming a file into place; mismatches are deleted |
| Remote processing is labeled | Every result shows "Processed on this device"; the Voice Studio screen shows "Processed on your Voice Studio server" when the remote path is used |

## Data inventory

* **Preferences** (DataStore, unencrypted, non-sensitive): languages, keyboard theme, toggles, Voice Studio URL.
* **Notes / history / dictionary** (Room `sema.db`): text fields encrypted; a lower-cased search index of the transcript is stored unencrypted to make search possible. If that trade-off is unacceptable for a deployment, set `searchable = false` in `NotesRepository.save` (search then only matches titles).
* **Note audio** (`filesDir/notes/<id>.wav`): only when the user chose to keep it.
* **Models** (`filesDir/models/<id>/`): public model weights, no user data.
* **Diagnostic logs**: Android logcat only, lifecycle and error messages, never content; off in release unless the user enables the setting.

## Permissions

* `RECORD_AUDIO`: dictation. Requested in context with an explanation (`perm_mic_rationale`).
* `INTERNET`, `ACCESS_NETWORK_STATE`: model downloads and the optional Voice Studio client only.
* `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`: the voice-note recording notification.
* `VIBRATE`: keyboard haptics.

## Future cloud features

Any future network feature must be disabled by default, require explicit consent, and show the
remote-processing label. `VoiceStudioClient` is the reference implementation of that pattern.
