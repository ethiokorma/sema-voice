# Sema Voice — PWA

A Progressive Web App version of Sema Voice. Dictation and translation run entirely in the
browser with [transformers.js](https://github.com/xenova/transformers.js) (Apache-2.0) on ONNX
Runtime Web; models are downloaded once and cached, so the app works offline afterwards and can
be installed to the home screen on Android, iOS (Safari "Add to Home Screen") and desktop.

What it can and cannot do:

* Can: record, transcribe (Amharic via the converted fine-tune, English/Arabic via stock Whisper
  tiny), clean punctuation, translate (OPUS-MT, Apache-2.0: X→English, English→Amharic/Arabic/
  Somali/Tigrinya), copy, share (Web Share API), save `.txt`, read aloud with system voices.
* Cannot: act as a system keyboard (browsers do not allow it), run in the background, or use the
  larger M2M100 model comfortably (630 MB; not offered here).

## Files

```
index.html            UI (English + Amharic strings, light/dark)
app.js                recording (MediaRecorder → 16 kHz PCM), whisper + translation pipelines, cleanup rules
sw.js                 service worker: offline app shell, cache-first for models and the CDN script
manifest.webmanifest  PWA manifest
icon.svg              icon
models/whisper-tiny-amharic/   ONNX export of b1n1yam/shook-tiny-amharic-600hr (Apache-2.0), int8
```

`models/whisper-tiny-amharic/onnx/*.onnx` are git-ignored (≈40 MB). Regenerate with:

```bash
pip install "optimum[onnxruntime]"
python -m optimum.exporters.onnx --model b1n1yam/shook-tiny-amharic-600hr \
    --task automatic-speech-recognition-with-past onnx-tiny-amharic
# then quantize encoder_model.onnx and decoder_model_merged.onnx with
# onnxruntime.quantization.quantize_dynamic(..., weight_type=QuantType.QUInt8)
# into models/whisper-tiny-amharic/onnx/{encoder_model_quantized,decoder_model_merged_quantized}.onnx
# and copy the *.json files (config, generation_config, preprocessor_config, tokenizer*) next to onnx/
```

## Run

Any static HTTPS host works (GitHub Pages, Cloudflare Pages, Netlify). Locally:

```bash
cd web && python -m http.server 8080     # then open http://localhost:8080
```

`localhost` counts as a secure context, so the microphone and the service worker work without
HTTPS. The Hugging Face CDN must be reachable the first time a stock model is used; the Amharic
model is served from this folder.

## Validation status (2026-10-12)

* ONNX export of the Amharic tiny fine-tune: done (`encoder_model_quantized.onnx` 9 MB,
  `decoder_model_merged_quantized.onnx` 47 MB, quantized with `EnableSubgraph`).
* The exported graph was verified with ONNX Runtime (Python, `optimum.onnxruntime`): FLEURS clip 000
  → "በሴቶች ቡድን ውስጥ አራት የበረዶ ተንሸራቶች ሩጫቸዋን መጨረስ አልቻሉም…" (reference "…ተንሸራታቾች ሩጫቸውን…"),
  12.8 s inference for 14.8 s of audio on the build host CPU.
* `tokenizer.json` was patched so transformers.js 2.x can read it (merges written as `"a b"`
  strings instead of `["a","b"]` pairs emitted by newer `tokenizers`).
* transformers.js **v2.17 failed** to load the export (its ONNX Runtime rejects the opset-18
  graphs; in the browser this surfaced as "Unsupported model type: whisper" from the fallback
  loader). The app now uses **@huggingface/transformers 3.3.3**. `test/transcribe-node.mjs`
  with v3 transcribes FLEURS clip 000 end to end in Node: load 1.4 s, inference 8.3 s for 14.8 s of
  audio, output "በሴቶች ቡድን ውስጥ አራት የበረዶ ተንሸራቶች ሩጫቸዋን መጨረስ አልቻሉም በጉዙፉ ስላሉም ካሉት ከመቶ አስራ ሰባት…"
  (reference "…ከ117ቱ…45ቱ…", i.e. numbers come out as words). Browser confirmation on a phone is
  the remaining step.

## Privacy

Same policy as the Android app: no server, no account, no analytics; audio stays in the tab.
`localStorage` only stores the chosen languages and UI language.
