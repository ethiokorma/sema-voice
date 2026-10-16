"""Adds repetition-guarded metrics to existing docs/ai-quality-*.json files without re-running
inference (raw rows are untouched), and writes a comparison table.

    python tests/ai-quality/rescore.py docs/ai-quality-*.json --csv benchmarks/amharic-model-comparison.csv
"""
import argparse
import csv
import glob
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from evaluate import guarded_metrics  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--csv")
    ap.add_argument("--json")
    a = ap.parse_args()
    paths = sorted({p for pat in a.files for p in glob.glob(pat)})
    table = []
    for p in paths:
        d = json.load(io.open(p, encoding="utf-8"))
        if "rows" not in d or not d["rows"]:
            continue
        d.update(guarded_metrics(d["rows"]))
        io.open(p, "w", encoding="utf-8").write(json.dumps(d, ensure_ascii=False, indent=2))
        table.append({k: (round(d[k], 4) if isinstance(d.get(k), float) else d.get(k)) for k in
                      ["model", "utterances", "wer", "cer", "wer_guarded", "cer_guarded", "repetition_incidence",
                       "needs_review_rate", "clips_wer_below_0_5", "mean_rtf", "host", "params"]})
    table.sort(key=lambda r: r["wer"])
    for r in table:
        print(f"{r['model']:<44} WER {r['wer']:.3f} CER {r['cer']:.3f} | guarded {r['wer_guarded']:.3f}/{r['cer_guarded']:.3f} | loops {r['repetition_incidence']:.2f} | <0.5: {r['clips_wer_below_0_5']} | RTF {r['mean_rtf']:.2f}")
    if a.csv:
        os.makedirs(os.path.dirname(a.csv), exist_ok=True)
        with open(a.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(table[0].keys())); w.writeheader(); w.writerows(table)
    if a.json:
        io.open(a.json, "w", encoding="utf-8").write(json.dumps({"dataset": "FLEURS am_et test, first 30 utterances", "normalization": "NFC, Ethiopic punctuation -> space, non-word chars removed, lower-case; WER = word edit distance / reference words; CER on characters without spaces", "results": table}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
