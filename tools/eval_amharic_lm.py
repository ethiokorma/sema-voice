"""Evaluate greedy CTC vs CTC + word n-gram LM beam search on cached Ethio-ASR log-probs
(written by tools/cache_ctc_logits.py), with the exact scoring of tests/ai-quality/evaluate.py
(corpus WER = word edits / reference words; CER on characters without spaces).

    # one setting
    python tools/eval_amharic_lm.py --cache <dir> --lm server/lm/amharic-3gram.npz --alpha 0.5 --beta 1 --beam 16
    # grid search (tuning: use the DEV cache only)
    python tools/eval_amharic_lm.py --cache <dev dir> --lm ... --grid alpha=0.3,0.5,0.7 beta=0,1,2 --out grid.json
"""
import argparse
import itertools
import json
import os
import sys
import time

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "server"))
sys.path.insert(0, os.path.join(ROOT, "tests", "ai-quality"))
from evaluate import edit_distance, normalize  # noqa: E402
from lm.decoder import LmDecoder, NgramLM  # noqa: E402

VOCAB = os.path.join(ROOT, "dist", "models", "ethio-asr-ctc", "vocab.json")


def load_cache(d, limit=0):
    index = json.load(open(os.path.join(d, "index.json"), encoding="utf-8"))
    stems = sorted(index)
    if limit:
        stems = stems[:limit]
    clips = []
    for s in stems:
        z = np.load(os.path.join(d, s + ".npz"))
        wins = [z[f"w{i}"].astype(np.float32) for i in range(len(z.files))]
        clips.append(dict(index[s], id=s, wins=wins))
    return clips


def score(rows):
    we = wn = ce = cn = 0
    for r in rows:
        rw, hw = normalize(r["ref"]).split(), normalize(r["hyp"]).split()
        we += edit_distance(rw, hw); wn += len(rw)
        rc, hc = "".join(rw), "".join(hw)
        ce += edit_distance(list(rc), list(hc)); cn += len(rc)
        r["wer"] = edit_distance(rw, hw) / max(1, len(rw))
    return {"wer": we / max(1, wn), "cer": ce / max(1, cn), "ref_words": wn}


def run_lm(clips, dec):
    rows, ms = [], 0.0
    for c in clips:
        t0 = time.perf_counter()
        hyp = " ".join(h for h in (dec.decode(w, log_probs=True) for w in c["wins"]) if h).strip()
        dt = (time.perf_counter() - t0) * 1000
        ms += dt
        rows.append({"id": c["id"], "ref": c["ref"], "hyp": hyp, "lm_decode_ms": round(dt, 1)})
    return rows, ms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", required=True)
    ap.add_argument("--lm", required=True, nargs="+")
    ap.add_argument("--lm-weights", type=float, nargs="*")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--alpha", type=float, default=0.5)
    ap.add_argument("--beta", type=float, default=1.0)
    ap.add_argument("--beam", type=int, default=16)
    ap.add_argument("--unk", type=float, default=0.0)
    ap.add_argument("--partial", type=float, default=-6.0)
    ap.add_argument("--min-logp", type=float, default=-7.0)
    ap.add_argument("--char-weight", type=float, default=0.5)
    ap.add_argument("--char-bonus", type=float, default=0.0)
    ap.add_argument("--grid", nargs="*", help="name=v1,v2 for alpha beta beam unk partial min_logp char_weight")
    ap.add_argument("--out")
    ap.add_argument("--rows", action="store_true", help="include per-clip rows in --out")
    a = ap.parse_args()
    clips = load_cache(a.cache, a.limit)
    audio_s = sum(c["audio_s"] for c in clips)
    ac_ms = sum(c["acoustic_ms"] for c in clips)
    greedy_rows = [{"id": c["id"], "ref": c["ref"], "hyp": c["greedy"]} for c in clips]
    g = score(greedy_rows)
    print(f"clips {len(clips)} audio {audio_s:.1f}s  GREEDY WER {g['wer']:.4f} CER {g['cer']:.4f}", flush=True)
    lm = [NgramLM(p) for p in a.lm]
    base = dict(alpha=a.alpha, beta=a.beta, beam=a.beam, unk=a.unk, partial=a.partial, min_logp=a.min_logp,
                char_weight=a.char_weight, char_bonus=a.char_bonus)
    grid = {}
    for spec in a.grid or []:
        k, vs = spec.split("=")
        grid[k] = [type(base[k])(float(v)) if k != "beam" else int(v) for v in vs.split(",")]
    keys = list(grid)
    results = []
    for combo in itertools.product(*[grid[k] for k in keys]) if keys else [()]:
        p = dict(base, **dict(zip(keys, combo)))
        dec = LmDecoder(None, VOCAB, alpha=p["alpha"], beta=p["beta"], beam=p["beam"], unk_penalty=p["unk"],
                        partial_penalty=p["partial"], token_min_logp=p["min_logp"],
                        char_weight=p["char_weight"], char_bonus=p["char_bonus"], lm=lm, lm_weights=a.lm_weights)
        rows, ms = run_lm(clips, dec)
        s = score(rows)
        r = dict(p, wer=s["wer"], cer=s["cer"], lm_decode_ms=round(ms, 1),
                 lm_decode_rtf=ms / 1000 / audio_s, lm_overhead_vs_acoustic=ms / ac_ms)
        results.append(r)
        print(json.dumps({k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()}), flush=True)
        if a.rows:
            r["rows"] = rows
            score(greedy_rows)
            for x, gr in zip(rows, greedy_rows):
                x["greedy"], x["greedy_wer"] = gr["hyp"], gr["wer"]
    best = min(results, key=lambda r: (r["wer"], r["cer"]))
    print("BEST", json.dumps({k: (round(v, 4) if isinstance(v, float) else v) for k, v in best.items() if k != "rows"}))
    if a.out:
        json.dump({"cache": a.cache, "lm": a.lm, "lm_weights": a.lm_weights, "clips": len(clips), "audio_s": audio_s, "acoustic_ms": ac_ms,
                   "greedy": g, "results": results}, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
