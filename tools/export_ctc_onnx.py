"""Exports a w2v-BERT 2.0 CTC model (e.g. badrex/Ethio-ASR-amharic, snapwre/hohe-asr-amharic) to
ONNX + int8 for OnnxCtcEngine, and writes vocab.json, ctc_config.json and catalog entries with SHA-256.

    pip install torch transformers onnx onnxruntime huggingface_hub soundfile
    python tools/export_ctc_onnx.py --repo badrex/Ethio-ASR-amharic --out dist/models/ethio-asr-ctc

Output dir:
    model_int8.onnx   (single file, ~610 MB)   <- what the app downloads
    vocab.json, ctc_config.json, catalog.json (sizes + SHA-256)
The fp32 graph (model.onnx + external weights, ~2.4 GB) is kept in <out>/fp32 for validation only.
"""
import argparse
import glob
import hashlib
import json
import os
import sys

import torch


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--id", default=None, help="catalog id, default derived from repo")
    a = ap.parse_args()
    from huggingface_hub import snapshot_download
    from transformers import AutoModelForCTC, AutoProcessor
    from onnxruntime.quantization import QuantType, quantize_dynamic

    src = snapshot_download(a.repo, allow_patterns=["*.json", "*.safetensors", "*.txt", "vocab*", "README.md"])
    card = open(os.path.join(src, "README.md"), encoding="utf-8", errors="ignore").read(4000).lower() if os.path.exists(os.path.join(src, "README.md")) else ""
    if "cc-by-nc" in card or "non-commercial" in card or "noncommercial" in card:
        sys.exit("model card declares a non-commercial licence; refusing")
    proc = AutoProcessor.from_pretrained(src)
    model = AutoModelForCTC.from_pretrained(src).eval()

    class Wrap(torch.nn.Module):
        def __init__(self, m):
            super().__init__(); self.m = m

        def forward(self, input_features, attention_mask):
            return self.m(input_features=input_features, attention_mask=attention_mask).logits

    os.makedirs(os.path.join(a.out, "fp32"), exist_ok=True)
    dummy = proc(torch.zeros(16000 * 3).numpy(), sampling_rate=16000, return_tensors="pt")
    f32 = os.path.join(a.out, "fp32", "model.onnx")
    torch.onnx.export(Wrap(model), (dummy["input_features"], dummy["attention_mask"]), f32,
                      input_names=["input_features", "attention_mask"], output_names=["logits"],
                      dynamic_axes={"input_features": {0: "b", 1: "t"}, "attention_mask": {0: "b", 1: "t"}, "logits": {0: "b", 1: "t"}},
                      opset_version=17, dynamo=False)
    i8 = os.path.join(a.out, "model_int8.onnx")
    # MatMul-only: ORT's CPU EP has no ConvInteger kernel, so quantizing the conformer convs
    # produces a model that fails to load (NOT_IMPLEMENTED ConvInteger). Measured result of this
    # setting on Ethio-ASR: 876 MB, WER 0.235 vs 0.219 fp32 on 30 FLEURS clips.
    quantize_dynamic(f32, i8, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])

    vocab = json.load(open(os.path.join(src, "vocab.json"), encoding="utf-8"))
    if isinstance(next(iter(vocab.values())), dict):  # multilingual nested vocab
        vocab = next(iter(vocab.values()))
    json.dump(vocab, open(os.path.join(a.out, "vocab.json"), "w", encoding="utf-8"), ensure_ascii=False)
    blank = vocab.get("[PAD]", model.config.pad_token_id)
    json.dump({"blank_id": blank, "word_delimiter": "|", "languages": ["am"], "sample_rate": 16000,
               "front_end": "SeamlessM4TFeatureExtractor(80 mel, stride 2)", "source": a.repo},
              open(os.path.join(a.out, "ctc_config.json"), "w"), indent=2)

    entries = [{"file": f, "sizeBytes": os.path.getsize(os.path.join(a.out, f)), "sha256": sha256(os.path.join(a.out, f))}
               for f in ["model_int8.onnx", "vocab.json", "ctc_config.json"]]
    cat = {"id": a.id or a.repo.split("/")[-1].lower() + "-ctc-int8", "format": "onnx-ctc", "sourceRepo": a.repo, "files": entries}
    json.dump(cat, open(os.path.join(a.out, "catalog.json"), "w"), indent=2)
    print(json.dumps(cat, indent=2))


if __name__ == "__main__":
    main()
