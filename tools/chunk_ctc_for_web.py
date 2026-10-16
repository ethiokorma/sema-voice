"""Splits a single-file CTC ONNX model into <100 MB chunks for GitHub Pages and writes the
manifest that web/worker.js reads (models/ethio-ctc/manifest.json).

    python tools/chunk_ctc_for_web.py --model path/to/model.onnx --vocab path/to/vocab.json \
        --out web/models/ethio-ctc --version 2026.10-int8mm
"""
import argparse
import hashlib
import json
import os

import onnx


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--vocab", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--chunk-mb", type=int, default=90)
    a = ap.parse_args()

    m = onnx.load(a.model, load_external_data=False)
    if any(t.data_location == onnx.TensorProto.EXTERNAL for t in m.graph.initializer):
        raise SystemExit("model uses external data; save it as a single file first")
    mask = next((i for i in m.graph.input if i.name == "attention_mask"), None)
    mask_type = "int64" if mask is not None and mask.type.tensor_type.elem_type == onnx.TensorProto.INT64 else "int32"
    ops = sorted({n.op_type for n in m.graph.node})

    vocab = json.load(open(a.vocab, encoding="utf-8"))
    blank = vocab.get("[PAD]")
    os.makedirs(a.out, exist_ok=True)
    for f in os.listdir(a.out):
        if f.startswith("model.part"):
            os.remove(os.path.join(a.out, f))

    data = open(a.model, "rb").read()
    size = a.chunk_mb * 1024 * 1024
    chunks = []
    for i in range(0, len(data), size):
        part = data[i:i + size]
        name = f"model.part{len(chunks):02d}"
        open(os.path.join(a.out, name), "wb").write(part)
        chunks.append({"file": name, "size": len(part), "sha256": hashlib.sha256(part).hexdigest()})
    manifest = {"version": a.version, "total": len(data), "mask_type": mask_type, "blank_id": blank,
                "ops": ops, "chunks": chunks, "vocab": vocab,
                "source": "badrex/Ethio-ASR-amharic (CC-BY-4.0), ONNX int8 by Sema Voice"}
    json.dump(manifest, open(os.path.join(a.out, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False)
    print(json.dumps({k: v for k, v in manifest.items() if k != "vocab"}, indent=1))


if __name__ == "__main__":
    main()
