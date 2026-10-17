"""Build the Amharic word 3-gram LM used by server/lm/decoder.py (pure Python + numpy, no KenLM).

Stages (each writes into --work, a scratch dir):
    python tools/build_amharic_lm.py corpus --wiki <wikimedia/wikipedia 20231101.am parquet> \
        --fineweb <HuggingFaceFW/fineweb-2 data/amh_Ethi/train parquet> \
        --fleurs-tsv <google/fleurs data/am_et/test.tsv> <.../dev.tsv> --work <dir>
    python tools/build_amharic_lm.py lm --work <dir> --out server/lm/amharic-3gram.npz \
        [--vocab-size 500000] [--min-count2 1] [--min-count3 2]

corpus: NFC, '::'/'፡፡' -> '።', sentence split on ። ፧ ! ? newline and non-decimal '.', then the exact
        scorer normalisation from tests/ai-quality/evaluate.py (Ethiopic punctuation -> space, other
        non-word chars removed, lower-case). Keeps sentences of >= 2 words whose characters are all in
        the acoustic model's vocab and that are >= 80% Ethiopic letters. Exact duplicates are dropped.
        LEAKAGE: drops every sentence that (a) equals a FLEURS am_et test/dev reference, (b) shares any
        word 8-gram with one, or (c) shares a word 4-gram with a reference and has >= 80% of its
        character 5-grams (spaces removed) contained in that reference (near-copies / fragments).
lm:     interpolated modified Kneser-Ney (3 discounts per order, Chen & Goodman), vocabulary capped to
        the most frequent words (others -> <unk>); n-grams below the min counts are pruned and the
        model is stored in backoff form:
            log p(w|u v) = P3[uvw] if stored else B2[uv] + (P2[vw] if stored else B1[v] + P1[w])
        (B2 = 0 when uv is not stored). Natural logs, float16 tables, int64 keys (21 bits per word id).
        Counting is partitioned by ranges of the first word id so peak memory stays a few GB.
"""
import argparse
import collections
import io
import json
import os
import re
import sys
import time
import unicodedata

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tests", "ai-quality"))
from evaluate import normalize  # noqa: E402  (exact scorer normalisation)

VOCAB_JSON = os.path.join(ROOT, "dist", "models", "ethio-asr-ctc", "vocab.json")
SPLIT = re.compile(r"[።፧!?\n\r]+|::+|፡፡+|(?<!\d)\.(?!\d)")
BITS = 21
M1 = (1 << BITS) - 1
M2 = (1 << (2 * BITS)) - 1
UNK, BOS, EOS = 0, 1, 2


def allowed_chars() -> set:
    v = json.load(open(VOCAB_JSON, encoding="utf-8"))
    return {t for t in v if len(t) == 1 and normalize(t)}  # chars that survive normalisation


def is_ethiopic(c: str) -> bool:
    return "ሀ" <= c <= "፿"


def sentences(text: str):
    text = unicodedata.normalize("NFC", text).replace("[ለማስተካከል]", " ")
    for s in SPLIT.split(text):
        n = normalize(s)
        if n:
            yield n


def load_refs(paths):
    refs = set()
    for p in paths:
        for line in io.open(p, encoding="utf-8"):
            cols = line.rstrip("\n").split("\t")
            for c in cols[2:4]:  # raw_transcription, transcription
                n = normalize(c)
                if n:
                    refs.add(n)
    return sorted(refs)


class Leak:
    def __init__(self, refs):
        self.refs = [r.split() for r in refs]
        self.exact = set(refs)
        self.g8, self.g4 = set(), collections.defaultdict(set)
        self.shingles, self.r8 = [], []
        for i, w in enumerate(self.refs):
            for k in range(len(w) - 7):
                self.g8.add(tuple(w[k : k + 8]))
            for k in range(len(w) - 3):
                self.g4[tuple(w[k : k + 4])].add(i)
            self.r8.append({tuple(w[k : k + 8]) for k in range(len(w) - 7)})
            s = "".join(w)
            self.shingles.append({s[k : k + 5] for k in range(len(s) - 4)})
        self.hit_refs = set()

    def check(self, w) -> str:
        cand = set()
        for k in range(len(w) - 3):
            cand |= self.g4.get(tuple(w[k : k + 4]), set())
        if " ".join(w) in self.exact:
            self.hit_refs.add(self.refs.index(w))
            return "exact"
        if cand and len(w) >= 8 and any(tuple(w[k : k + 8]) in self.g8 for k in range(len(w) - 7)):
            g = {tuple(w[k : k + 8]) for k in range(len(w) - 7)}
            self.hit_refs |= {i for i in cand if g & self.r8[i]}
            return "ngram8"
        if cand:
            s = "".join(w)
            sh = {s[k : k + 5] for k in range(len(s) - 4)}
            for i in cand:
                if sh and len(sh & self.shingles[i]) / len(sh) >= 0.8:
                    self.hit_refs.add(i)
                    return "near"
        return ""


def read_parquet_texts(path, col="text"):
    import pyarrow.parquet as pq
    f = pq.ParquetFile(path)
    for g in range(f.metadata.num_row_groups):
        for t in f.read_row_group(g, columns=[col]).column(0).to_pylist():
            if t:
                yield t


def stage_corpus(a):
    os.makedirs(a.work, exist_ok=True)
    ok = allowed_chars()
    refs = load_refs(a.fleurs_tsv)
    leak = Leak(refs)
    stats = collections.OrderedDict()
    seen = set()
    removed = io.open(os.path.join(a.work, "leak_removed.txt"), "w", encoding="utf-8")
    out = io.open(os.path.join(a.work, "corpus.txt"), "w", encoding="utf-8")
    t0 = time.time()
    for name, path in (("wikipedia", a.wiki), ("fineweb2", a.fineweb)):
        if not path:
            continue
        st = collections.Counter()
        src_out = io.open(os.path.join(a.work, f"corpus_{name}.txt"), "w", encoding="utf-8")
        for doc in read_parquet_texts(path):
            st["documents"] += 1
            for s in sentences(doc):
                st["sentences_raw"] += 1
                w = s.split()
                if len(w) < 2 or any(c not in ok for c in s if c != " "):
                    st["dropped_charset_or_short"] += 1
                    continue
                letters = s.replace(" ", "")
                if sum(map(is_ethiopic, letters)) < 0.8 * len(letters):
                    st["dropped_non_ethiopic"] += 1
                    continue
                h = hash(s)
                if h in seen:
                    st["dropped_duplicate"] += 1
                    continue
                seen.add(h)
                why = leak.check(w)
                if why:
                    st["leak_removed_" + why] += 1
                    removed.write(f"{name}\t{why}\t{s}\n")
                    continue
                st["sentences_kept"] += 1
                st["words_kept"] += len(w)
                out.write(s + "\n")
                src_out.write(s + "\n")
            if st["documents"] % 20000 == 0:
                print(name, dict(st), f"{time.time()-t0:.0f}s", flush=True)
        src_out.close()
        stats[name] = dict(st)
        print(name, dict(st), flush=True)
    out.close()
    removed.close()
    stats["fleurs_reference_texts"] = len(refs)
    stats["fleurs_references_hit_by_removed_sentences"] = len(leak.hit_refs)
    json.dump(stats, open(os.path.join(a.work, "corpus_stats.json"), "w"), indent=1)
    print(json.dumps(stats, indent=1))


# ----------------------------------------------------------------------------------------- LM
def discounts(counts):
    u, c = np.unique(np.minimum(counts, 5), return_counts=True)
    coc = dict(zip(u.tolist(), c.tolist()))
    n1, n2, n3, n4 = (max(1, coc.get(i, 0)) for i in (1, 2, 3, 4))
    y = n1 / (n1 + 2 * n2)
    return np.array([0.0, 1 - 2 * y * n2 / n1, 2 - 3 * y * n3 / n2, 3 - 4 * y * n4 / n3])


def sum_by_key(keys, vals):
    u, inv = np.unique(keys, return_inverse=True)
    return u, np.bincount(inv, weights=vals)


def lookup(sorted_keys, q):
    j = np.minimum(np.searchsorted(sorted_keys, q), len(sorted_keys) - 1)
    return j, sorted_keys[j] == q


def char_lm(word_counts, order=6, min_count=2, max_word_count=2, max_types=400000):
    """Character n-gram model of how *rare* words are spelled (stands in for OOV words).
    Interpolated absolute discounting over chars with N-1 <w> pads and a </w> end symbol; trained on
    tokens of words seen <= max_word_count times (subsampled to <= max_types types). Returns arrays for the npz (prefix ch_)."""
    chars = sorted({c for w in word_counts for c in w})
    cid = {c: i + 3 for i, c in enumerate(chars)}  # 0 unused, 1 = <w> pad, 2 = </w>
    cb = max(9, int(np.ceil(np.log2(len(chars) + 3))))
    assert cb * order <= 63
    seqs, wts = [], []
    rare = sorted(w for w, n in word_counts.items() if n <= max_word_count)
    step = max(1, len(rare) // max_types)  # deterministic subsample keeps memory bounded
    for w in rare[::step]:
        n = word_counts[w]
        if True:
            seqs.append([1] * (order - 1) + [cid[c] for c in w] + [2]); wts.append(n)
    out = {"ch_chars": np.frombuffer("".join(chars).encode("utf-8"), np.uint8),
           "ch_meta": np.array([order, cb])}
    prev = None
    for n in range(1, order + 1):
        keys, ws = [], []
        for s, wt in zip(seqs, wts):
            for i in range(order - 1, len(s)):
                k = 0
                for c in s[i - n + 1 : i + 1]:
                    k = (k << cb) | c
                keys.append(k); ws.append(wt)
        keys = np.array(keys, np.int64); ws = np.array(ws, np.float64)
        u, inv = np.unique(keys, return_inverse=True)
        cnt = np.bincount(inv, weights=ws)
        n1, n2 = max(1, int((cnt == 1).sum())), max(1, int((cnt == 2).sum()))
        D = min(0.95, n1 / (n1 + 2 * n2))
        ctx = u >> cb
        cu, cinv = np.unique(ctx, return_inverse=True)
        tot = np.bincount(cinv, weights=cnt)
        gam = D * np.bincount(cinv) / tot
        if n == 1:
            lower = np.full(len(u), 1.0 / (len(chars) + 2))
        else:
            pk, pp = prev
            j = np.minimum(np.searchsorted(pk, u & ((1 << (cb * (n - 1))) - 1)), len(pk) - 1)
            lower = pp[j]
        p = np.maximum(cnt - D, 0) / tot[cinv] + gam[cinv] * lower
        prev = (u, p)
        keep = (cnt >= min_count) if n > 2 else np.ones(len(u), bool)
        out[f"ch_k{n}"], out[f"ch_p{n}"] = u[keep], np.log(p[keep]).astype(np.float16)
        out[f"ch_ctx{n}"], out[f"ch_g{n}"] = cu, np.log(gam).astype(np.float16)
        print(f"char order {n}: {len(u)} types, stored {int(keep.sum())}, D={D:.3f}", flush=True)
    return out


def stage_lm(a):
    corpus = a.corpus or os.path.join(a.work, "corpus.txt")
    tmp = os.path.join(a.work, "lm_tmp")
    os.makedirs(tmp, exist_ok=True)
    t0 = time.time()
    log = lambda *x: print(*x, f"[{time.time()-t0:.0f}s]", flush=True)
    wc = collections.Counter()
    for line in io.open(corpus, encoding="utf-8"):
        wc.update(line.split())
    words = [w for w, _ in wc.most_common(a.vocab_size)]
    vocab = ["<unk>", "<s>", "</s>"] + words
    V = len(vocab)
    assert V < M1
    wid = {w: i for i, w in enumerate(vocab)}
    total_tokens = sum(wc.values())
    oov_tokens = total_tokens - sum(wc[w] for w in words)
    log(f"types {len(wc)} tokens {total_tokens} vocab {V} oov-rate {oov_tokens/total_tokens:.4f}")
    chlm = char_lm(wc, order=a.char_order) if a.char_order else {}
    del wc
    chunks, buf = [], []
    for line in io.open(corpus, encoding="utf-8"):
        buf.append(BOS)
        buf.extend(wid.get(w, UNK) for w in line.split())
        buf.append(EOS)
        if len(buf) > 5_000_000:
            chunks.append(np.array(buf, np.int32)); buf = []
    chunks.append(np.array(buf, np.int32))
    ids = np.concatenate(chunks)
    del chunks, buf, wid
    log("ids", ids.shape)
    # first-word ranges with roughly equal token mass -> globally sorted concatenation
    freq = np.bincount(ids, minlength=V)
    cum = np.cumsum(freq) / freq.sum()
    P = a.partitions
    bounds = [0] + [int(np.searchsorted(cum, (k + 1) / P)) + 1 for k in range(P - 1)] + [V]
    bounds = sorted(set(min(b, V) for b in bounds))
    u, v, w = ids[:-2], ids[1:-1], ids[2:]
    good3 = (u != EOS) & (v != EOS) & (v != BOS) & (w != BOS)
    a2, b2 = ids[:-1], ids[1:]
    good2 = (a2 != EOS) & (b2 != BOS)
    # ---- pass A: trigram types per partition; bigram raw counts; continuation counts
    cont_parts, c3_hist, bi_parts = [], collections.Counter(), []
    for k in range(len(bounds) - 1):
        lo, hi = bounds[k], bounds[k + 1]
        m = good3 & (u >= lo) & (u < hi)
        key = (u[m].astype(np.int64) << (2 * BITS)) | (v[m].astype(np.int64) << BITS) | w[m].astype(np.int64)
        tri, c3 = np.unique(key, return_counts=True)
        del key, m
        np.save(os.path.join(tmp, f"tri{k}.npy"), tri)
        np.save(os.path.join(tmp, f"c3_{k}.npy"), c3.astype(np.int32))
        c3_hist.update(dict(zip(*[x.tolist() for x in np.unique(np.minimum(c3, 5), return_counts=True)])))
        cont_parts.append(np.unique(tri & M2, return_counts=True))
        m2 = good2 & (a2 >= lo) & (a2 < hi)
        bkey = (a2[m2].astype(np.int64) << BITS) | b2[m2].astype(np.int64)
        bi_parts.append(np.unique(bkey, return_counts=True))
        del bkey, m2, tri, c3
        log(f"partition {k} ids [{lo},{hi}) trigram types {len(np.load(os.path.join(tmp, f'tri{k}.npy'), mmap_mode='r'))}")
    del u, v, w, good3, a2, b2, good2, ids
    bi_raw = np.concatenate([p[0] for p in bi_parts])
    c2_raw = np.concatenate([p[1] for p in bi_parts])
    del bi_parts
    ck = np.concatenate([p[0] for p in cont_parts])
    cv = np.concatenate([p[1] for p in cont_parts]).astype(np.float64)
    del cont_parts
    cont_keys, cont = sum_by_key(ck, cv)
    del ck, cv
    j, f = lookup(cont_keys, bi_raw)
    c2 = np.where(f, cont[j], 0).astype(np.int64)
    bos = (bi_raw >> BITS) == BOS
    c2[bos] = c2_raw[bos]  # nothing precedes <s>: use raw counts (as KenLM does)
    keep = c2 > 0
    bi, c2 = bi_raw[keep], c2[keep]
    del bi_raw, c2_raw, cont_keys, cont, j, f, bos, keep
    n = lambda i: max(1, c3_hist.get(i, 0))
    y = n(1) / (n(1) + 2 * n(2))
    D3 = np.array([0.0, 1 - 2 * y * n(2) / n(1), 2 - 3 * y * n(3) / n(2), 3 - 4 * y * n(4) / n(3)])
    D2 = discounts(c2)
    log("bigram types", len(bi), "D2", D2.round(3), "D3", D3.round(3))
    # ---- unigram (continuation counts, interpolated with uniform)
    c1 = np.bincount(bi & M1, minlength=V).astype(np.float64)
    c1[BOS] = 0
    p1 = 0.999 * c1 / c1.sum() + 0.001 / V
    p1[BOS] = 1e-30
    # ---- bigram
    ctx2 = bi >> BITS
    u2, inv2 = np.unique(ctx2, return_inverse=True)
    tot2 = np.bincount(inv2, weights=c2)
    gam2 = np.bincount(inv2, weights=D2[np.minimum(c2, 3)]) / tot2
    p2 = np.maximum(c2 - D2[np.minimum(c2, 3)], 0) / tot2[inv2] + gam2[inv2] * p1[bi & M1]
    del ctx2, inv2, tot2
    # ---- pass B: trigram probabilities, pruning, trigram-context backoffs
    tri_keep, tri_lp, ctx3_all, gam3_all = [], [], [], []
    stored3 = types3 = 0
    for k in range(len(bounds) - 1):
        tri = np.load(os.path.join(tmp, f"tri{k}.npy"))
        c3 = np.load(os.path.join(tmp, f"c3_{k}.npy")).astype(np.int64)
        types3 += len(tri)
        u3, inv3 = np.unique(tri >> BITS, return_inverse=True)
        tot3 = np.bincount(inv3, weights=c3)
        gam3 = np.bincount(inv3, weights=D3[np.minimum(c3, 3)]) / tot3
        j, f = lookup(bi, tri & M2)
        assert f.all()
        p3 = np.maximum(c3 - D3[np.minimum(c3, 3)], 0) / tot3[inv3] + gam3[inv3] * p2[j]
        keep = c3 >= a.min_count3
        tri_keep.append(tri[keep]); tri_lp.append(np.log(p3[keep]).astype(np.float16))
        ctx3_all.append(u3); gam3_all.append(gam3)
        stored3 += int(keep.sum())
        del tri, c3, inv3, tot3, j, f, p3, keep
        log(f"partition {k} stored trigrams so far {stored3}")
    tri_keys = np.concatenate(tri_keep); tri_lp = np.concatenate(tri_lp)
    ctx3 = np.concatenate(ctx3_all); gam3 = np.concatenate(gam3_all)
    del tri_keep, ctx3_all, gam3_all
    # bigrams stored: count threshold, plus every context of a stored trigram (needed for B2)
    keep2 = c2 >= a.min_count2
    j, f = lookup(bi, np.unique(tri_keys >> BITS))
    keep2[j[f]] = True
    bi_keys = bi[keep2]
    bi_lp = np.log(p2[keep2]).astype(np.float16)
    bo2 = np.zeros(len(bi_keys))
    j, f = lookup(ctx3, bi_keys)
    bo2[f] = np.log(gam3[j[f]])
    bo1 = np.zeros(V)
    bo1[u2] = np.log(gam2)
    np.savez(a.out, vocab=np.frombuffer("\n".join(vocab).encode("utf-8"), np.uint8),
             uni_lp=np.log(p1).astype(np.float32), uni_bo=bo1.astype(np.float32),
             bi_keys=bi_keys, bi_lp=bi_lp, bi_bo=bo2.astype(np.float16),
             tri_keys=tri_keys, tri_lp=tri_lp, bits=np.array([BITS]), **chlm)
    info = {"order": 3, "smoothing": "interpolated modified Kneser-Ney, count-pruned, backoff form",
            "vocab": V, "corpus_tokens": int(total_tokens), "corpus_oov_rate": oov_tokens / total_tokens,
            "trigram_types": int(types3), "stored_trigrams": int(len(tri_keys)), "bigram_types": int(len(bi)),
            "stored_bigrams": int(len(bi_keys)), "min_count2": a.min_count2, "min_count3": a.min_count3,
            "D2": D2.tolist(), "D3": D3.tolist(), "file_bytes": os.path.getsize(a.out)}
    json.dump(info, open(os.path.splitext(a.out)[0] + ".json", "w"), indent=1)
    log(json.dumps(info, indent=1))
    for k in range(len(bounds) - 1):
        for fn in (f"tri{k}.npy", f"c3_{k}.npy"):
            os.remove(os.path.join(tmp, fn))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("corpus")
    c.add_argument("--wiki"); c.add_argument("--fineweb")
    c.add_argument("--fleurs-tsv", nargs="+", required=True)
    c.add_argument("--work", required=True)
    l = sub.add_parser("lm")
    l.add_argument("--work", required=True); l.add_argument("--out", required=True)
    l.add_argument("--corpus", help="corpus file (default <work>/corpus.txt; e.g. <work>/corpus_wikipedia.txt)")
    l.add_argument("--vocab-size", type=int, default=500000)
    l.add_argument("--min-count2", type=int, default=1)
    l.add_argument("--min-count3", type=int, default=2)
    l.add_argument("--partitions", type=int, default=8)
    l.add_argument("--char-order", type=int, default=6, help="char LM for OOV words (0 = none)")
    a = ap.parse_args()
    stage_corpus(a) if a.cmd == "corpus" else stage_lm(a)


if __name__ == "__main__":
    main()
