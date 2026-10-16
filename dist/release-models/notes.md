Offline speech models for Sema Voice, downloaded on demand by the app's model manager and verified by SHA-256 (see catalog.json).

**ethio-asr-ctc-*** — Amharic w2v-BERT 2.0 CTC, ONNX int8 (MatMul-only), exported from [badrex/Ethio-ASR-amharic](https://huggingface.co/badrex/Ethio-ASR-amharic). License **CC-BY-4.0**. Attribution: Ethio-ASR, Badr M. Abdullah et al. (arXiv:2603.23654), trained on the WAXAL Amharic corpus (Google WaxalNLP). Modified: converted to ONNX and int8-quantized. Measured on 30 FLEURS am_et test clips: WER 0.235, CER 0.086.

**ggml-tiny-amharic-q5_1.bin** — Whisper tiny Amharic fine-tune [b1n1yam/shook-tiny-amharic-600hr](https://huggingface.co/b1n1yam/shook-tiny-amharic-600hr), Apache-2.0, converted to whisper.cpp ggml and q5_1-quantized. Base model OpenAI Whisper (MIT). Measured WER 0.812 raw / 0.618 with Sema Voice's repetition guard.
