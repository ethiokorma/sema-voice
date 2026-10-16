# CLOUD_PRIVACY: optional cloud recognition and translation

**Status: designed, not implemented.** Two things are missing:

* A cloud account with API keys, which belong to the publisher.
* A small proxy server that the publisher operates.

Without both, a provider cannot be built or tested honestly. The offline path stays the
default and the only one that ships today.

## Research summary (2026-10-15, vendor pages; [UV] = unverified)

| Provider | Amharic STT | Price | Notes |
|---|---|---|---|
| Google Cloud STT v2, Chirp 3 | Yes (am-ET), **Preview** | About $0.016/min [UV] | Streaming and batch. Regions for am-ET are unclear. |
| Gemini 2.5 / 3 Flash (audio input) | Not documented. A third party reports 18.9 % FLEURS WER [UV]. | About $0.002/min (token-based) | Paid tier is not used for training. |
| ElevenLabs Scribe v1 | Yes | $0.40/h | The vendor page contradicts itself (3.1 % vs 29.9 % FLEURS WER). Batch only. |
| AssemblyAI Universal-2 | Yes | $0.15/h | Cheapest with a documented Amharic page. No published Amharic WER. |
| Azure Speech | Yes (am-ET) | About $1/h [UV], 5 h/month free | Also has am-ET neural TTS voices (Mekdes, Ameha). |
| AWS Transcribe | Streaming only (2024) | About $0.024/min [UV] | |
| Gladia | Yes | Not listed | |
| Deepgram, Speechmatics, Soniox | **No Amharic** | | |
| OpenAI whisper-1, gpt-4o-transcribe | Not credible for Amharic | | Whisper large-v3 scores about 100 % WER |
| Intron Sahara | Possibly (57-language release) | Unknown | |
| "Vocalbox" | Nothing found | | Need a URL from the product owner |

Translation APIs:

* **Google Translate NMT:** Amharic supported, about $20 per 1M characters [UV].
* **Azure Translator:** Amharic and Tigrinya.
* **DeepL:** no Amharic.

TTS: Azure has am-ET neural voices. Google Cloud TTS am-ET is unconfirmed. ElevenLabs v3 does not
list Amharic.

## Recommendation

* None of the vendors publishes a credible Amharic WER, and the measured offline CTC models
  reach 0.22 WER. Cloud should be **benchmarked before it is built**.
* Run the 30-clip FLEURS set through Gemini Flash, AssemblyAI, Chirp 3 and ElevenLabs. Then
  implement only a provider that clearly beats about 0.20 WER.

## Mandatory design for any cloud provider

1. **Opt-in only.** It is off by default, enabled per feature in Settings, with a consent dialog
   that says "your audio leaves this phone and is sent to <vendor> via <your server>".
2. **No keys in the APK.** The app calls a publisher-run proxy, for example FastAPI on Cloud Run.
   * The proxy holds the vendor key.
   * It authenticates the app with per-install tokens.
   * It enforces quotas and an upload size limit, and keeps a 30 s timeout.
3. **No retention by default.** The proxy streams audio to the vendor and discards it. Logs hold
   no transcript content.
4. **Visible status.** Every cloud result is labelled "Processed in the cloud (<vendor>)".
   The existing `TranslationResult.processedLocally` and the Voice Studio remote label are the
   pattern to follow.
5. **No silent switching.** If the offline engine fails, the app offers cloud as an action; it
   never switches automatically. Offline-only mode hard-blocks every network call except model
   downloads.
6. The provider plugs in behind the same `SpeechRecognitionEngine` / `TranslationEngine` contract.
   `DictationPipeline` already selects engines per model `format`; a cloud entry would use
   `format = "cloud-<vendor>"`.

The existing `VoiceStudioClient` already implements the consent gate (enabled + consent flags,
http(s) URL validation) and serves as the template.
