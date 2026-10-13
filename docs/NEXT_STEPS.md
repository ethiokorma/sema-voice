# NEXT_STEPS — continuation plan (written 2026-10-12, resume Wednesday night)

Everything below is ordered by priority. Each item states the symptom or goal, what is already known,
the exact approach, and how to prove it worked. Repo: https://github.com/ethiokorma/sema-voice
(`main` = project, `gh-pages` = PWA). Live PWA: https://ethiokorma.github.io/sema-voice/

---

## 1. PWA crashes on the phone after one transcription (highest priority)

**Symptom reported:** page crashes after one attempt; after reload the model downloads again.
**Known:** the model and pipeline work end to end in Node with the identical library
(transformers.js 3.3.3) and files (`web/test/transcribe-node.mjs`, 8.3 s for 14.8 s audio). So the
graph is fine; the failure is browser/phone resource related. Last deployed mitigations (untested on
the phone): 30 s utterance cap, single decoder window, `max_new_tokens: 224`, service worker no
longer caches files over 8 MB.

**Diagnosis steps (first 20 minutes of the session):**
1. Ask: phone model + RAM, Chrome version, whether the crash happens (a) during download,
   (b) during transcription, (c) after text appears, (d) on the second tap.
2. Reproduce on desktop Chrome with DevTools → Memory; and with the Android phone over USB:
   `chrome://inspect` remote debugging shows the real console error and memory. If the tab shows
   "Aw, Snap" with code `SIGSEGV`/`out of memory`, it is memory.
3. Check Cache Storage in DevTools (Application tab): confirm `transformers-cache` holds the
   `.onnx` files after a successful load. If it never fills, the download path is the problem.

**Fix ladder (apply in order until stable on a 4 GB phone):**
1. **Move inference into a Web Worker** (`web/worker.js`): the page stays alive, errors are
   reported as text instead of a dead tab, and the main thread never blocks. transformers.js v3
   supports this directly (`new Worker('worker.js', {type:'module'})`, post `Float32Array` with
   transfer). This is the first thing to implement regardless.
2. **Smaller decoder.** The merged decoder is 47 MB because the merged graph stores both the
   no-past and with-past branches. Options: (a) export without `--task ...-with-past` merge and ship
   `decoder_model_quantized.onnx` + `decoder_with_past_model_quantized.onnx` (transformers.js v3
   accepts the split form when `decoder_model_merged` is absent), roughly 25 + 24 MB but only one
   resident at a time is not guaranteed; (b) `dtype: { encoder_model: 'q8', decoder_model_merged: 'q4' }`
   with a q4 export (optimum `--quantize`/onnxruntime matmul_4bits) ~ 15 MB decoder; (c) keep q8 but
   free the ORT session between runs (`await asr.dispose()` after each transcription, reload from
   cache next time, ~1.4 s). Start with (c) since it is one line and proves whether residency is the
   issue.
3. **ORT-web settings:** `env.backends.onnx.wasm.numThreads = 1` (GitHub Pages has no COOP/COEP so
   threads are off anyway, but make it explicit), `env.backends.onnx.wasm.proxy = false` when using a
   worker, and `simd = true`.
4. **Audio path:** decode the MediaRecorder blob with a single `OfflineAudioContext` at 16 kHz
   (currently two contexts are created; the first `decodeAudioData` on a 16 kHz context already
   resamples, so the second render is redundant). Release the Blob/ArrayBuffer references.
5. **Fallback UI:** if `performance.memory`/`navigator.deviceMemory` reports < 3 GB, show a notice
   and default to 15 s utterances.

**Proof:** on the user's phone: 5 consecutive transcriptions without a reload; model not
re-downloaded after a reload (Application → Cache Storage shows it). Record the phone model and
timings in `docs/TEST_RESULTS.md`.

## 2. Android app on a real phone (never done)

Checklist is in `docs/MODEL_COMPATIBILITY.md` → "Device validation checklist". Expect first-run
issues in: IME Compose lifecycle (`SemaInputMethodService`), `AudioRecord` on 48 kHz-only devices
(resampler path), `startForegroundService` notification permission on Android 13+, ONNX translation
memory with M2M100 (630 MB; use OPUS-MT first). Run `./gradlew connectedDebugAndroidTest` for the
Room encryption tests. Produce the first on-device RTF numbers for `TEST_RESULTS.md`.

Hosting the Amharic model for the app: create HF repo `ethiokorma/sema-voice-models`, upload
`dist/models/ggml-tiny-amharic-q5_1.bin` (+ f16 variant) and `dist/models/catalog.json`; the app's
default catalog URL currently points at `huggingface.co/semavoice/sema-voice-models` — change
`AMHARIC_MODEL_BASE_URL` in `ModelCatalog.kt` and the default `modelSourceUrl` in
`SettingsRepository.kt` to the real repo, or enter it in Settings → model catalog URL.

## 3. Amharic quality: repetition loops

Measured: tiny fine-tune WER 0.81 raw on 30 FLEURS clips; 12/30 clips under 0.5; 6/30 above 1.0
purely from repeated tails; first clip near perfect. Numbers are written as words ("ከመቶ አስራ ሰባት"
for 117) — acceptable, note it in UX.

Mitigations to measure with `tests/ai-quality/evaluate.py` (whisper.cpp params in
`sema_whisper_jni.cpp`; for the PWA use the equivalent transformers.js generate options):
* `max_tokens` per segment ≈ 4 × seconds of audio (Ge'ez is ~3 tokens per character), hard cap 224.
* `entropy_thold` 2.4 → 2.0 and `logprob_thold` -1.0 → -0.8 to trigger the temperature fallback earlier.
* `no_repeat_ngram_size`-style guard in post-processing: collapse a repeated run of ≥ 3 *tokens*
  (not words; an exact-word collapser did not help because repeats vary slightly) — implement on
  the segment token ids before detokenizing (whisper.cpp exposes `whisper_full_get_token_id`).
* Suppress the fallback loop by `single_segment = true` for utterances ≤ 30 s (keyboard use case).

Better model path: the research fine-tune (arXiv:2503.18485) reaches ~31 % WER with whisper-small
on Common Voice + FLEURS; reproduce it ourselves (Colab T4, ~2 h) from CC-BY/CC0 data and publish
under Apache-2.0. That removes the dependency on third-party checkpoints with undocumented data.

## 4. Omnilingual ASR (Meta, Apache-2.0) as a second engine

Why: CTC decoding cannot loop; covers Tigrinya and Afaan Oromoo; 300M model ≈ 330 MB int8.
Plan:
1. `pip install omnilingual-asr` (fairseq2); load `omniASR_CTC_300M`, run on the FLEURS clips with
   `evaluate.py`-style scoring to get WER/CER vs the Whisper fine-tune. If Amharic WER is not clearly
   better than 0.5, stop here.
2. Export: wav2vec2-style encoder + CTC head → ONNX via `torch.onnx.export` (dynamic time axis),
   int8 dynamic quantization; the CTC vocabulary (character level) ships as a JSON. Greedy CTC
   decoding is 30 lines of Kotlin/JS; no tokenizer model needed.
3. Android: new module `core/speech-ctc` implementing `SpeechRecognitionEngine` with ONNX Runtime
   (already a dependency). Model catalog entry kind SPEECH, format `onnx-ctc`. `ModelManager`
   picks it when the language is ti/om, or when the user prefers it.
4. PWA: same ONNX + tiny JS decoder; 330 MB is heavy for a browser, so Android first.

## 5. Release engineering

* Signing: `keytool -genkeypair` → `signingConfigs.release` in `app/build.gradle.kts` (never commit
  the keystore; use `~/.gradle/gradle.properties` for passwords).
* `./gradlew bundleRelease` → App Bundle so Play serves one ABI (~45 MB instead of 83 MB).
* Play listing + Data safety form: no data collected; microphone used on device only. Link
  `docs/PRIVACY.md` as the policy page (GitHub Pages can host it at
  `https://ethiokorma.github.io/sema-voice/privacy.html`).
* 16 KB page compliance already verified; target SDK 35.

## 6. Android keyboard: Ge'ez letter layout

The Android IME has phonetic Ge'ez composition (`GeezComposer`) but Latin-labelled keys. Port the
PWA's letter layout (`web/keyboard.js`: 34 families + vowel-form picker row) into
`KeyboardView.kt` as a third layout (`ፊደል` toggle already exists), sharing `GEEZ_ROWS` constants.

## 7. iOS (needs a Mac)

See `docs/IOS_PLAN.md`; nothing can be compiled here.

---

## Research notes gathered this session (so they are not redone)

* Stock Whisper on Amharic: tiny q5_1 measured WER 1.29 / CER 1.66 on FLEURS (paper: 120–230 %).
* `seyyaw/whisper-finetuned-amharic` (small): outputs Hebrew/Latin garbage → rejected.
* `b1n1yam/shook-tiny-amharic-600hr` (tiny, Apache-2.0): usable; converted files + SHA-256 in
  `dist/models/catalog.json` (git-ignored binaries are in the local `dist/models/` folder on this PC
  and on the `gh-pages` branch under `models/whisper-tiny-amharic/` in ONNX form).
* Translation: M2M100-418M (MIT) both directions, ~630 MB int8; OPUS-MT mul-en / en-mul
  (Apache-2.0) ~115 MB each, en→am weak (BLEU ~8.5). NLLB and all Amharic NLLB fine-tunes are
  CC-BY-NC. ML Kit and Firefox Translations have no Amharic.
* TTS: no commercially licensed offline Amharic model (MMS-TTS and OmniVoice derivatives are
  CC-BY-NC); Android system TTS has an am-ET voice; browsers depend on installed system voices.
* Voice Studio repos: debpalash/VoiceStudio AGPL-3.0 + NC default weights (HTTP only);
  msrbuilds/voice-studio MIT with `POST /api/synthesize`.
* Google Chirp 3: cloud-only, per-minute billing → optional consent-gated fallback at most.
* SeamlessM4T v2: CC-BY-NC-4.0 → rejected.
* transformers.js 2.17 cannot load opset-18 exports (surfaces as "Unsupported model type: whisper");
  v3.3.3 works. `tokenizer.json` merges must be `"a b"` strings for v2, v3 accepts both.
* Build pitfalls on this PC: Bash heredocs unescape `\uXXXX` and `\\`, so write sources with the
  Write tool; `pos += readVarint()` evaluation order bug pattern in Kotlin; `VERSION` file name
  shadows the C++ `<version>` header on case-insensitive filesystems; onnxruntime-node 1.14 crashes
  here (0xC06D007F) but 1.20 (v3) works.
