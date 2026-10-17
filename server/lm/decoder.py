"""CTC prefix beam search with a word n-gram LM (pure Python + numpy; no KenLM / pyctcdecode needed).

    from lm.decoder import LmDecoder
    dec = LmDecoder("server/lm/amharic-3gram.npz", "dist/models/ethio-asr-ctc/vocab.json",
                    alpha=0.5, beta=1.0, beam=16)
    text = dec.decode(logits)          # logits: np.ndarray [T, V'] raw CTC logits (V' >= len(vocab))

Scoring of a hypothesis (natural logs):
    log P_ctc(prefix) + alpha * sum log P_lm(word | 2 previous words) + beta * #words
    + char_bonus * #word characters
    where an out-of-vocabulary word scores log P_lm(<unk>|ctx) + char_weight * log P_char(spelling)
    + unk_penalty, and an unfinished word that is not a prefix of any LM word scores
    alpha * (char_weight * log P_char(prefix) + partial_penalty) while it is being extended.
    At the end the unfinished word and </s> are scored.
Word boundaries are the "|" token and every token that the evaluation normaliser turns into a space
(Ethiopic/ASCII punctuation), so LM words match the scorer's (and the LM corpus's) tokenisation.
Columns of `logits` beyond the vocab are ignored (log-softmax is taken over the vocab columns only).
"""
from __future__ import annotations

import bisect
import json
import math
import re
import unicodedata

import numpy as np

NEG = -1e30
UNK, BOS, EOS = 0, 1, 2
_PUNCT = "፡።፣፤፥፦፧፨"


def _is_word_char(tok: str) -> bool:
    t = unicodedata.normalize("NFC", tok).translate({ord(c): " " for c in _PUNCT})
    return bool(re.sub(r"[^\w\s]", " ", t).strip())


def _lse(a: float, b: float) -> float:
    if a < b:
        a, b = b, a
    if b <= NEG:
        return a
    return a + math.log1p(math.exp(b - a))


class NgramLM:
    """Backoff 3-gram LM written by tools/build_amharic_lm.py (npz, natural-log probabilities)."""

    def __init__(self, path: str):
        z = np.load(path)
        self.vocab = bytes(z["vocab"]).decode("utf-8").split("\n")
        self.wid = {w: i for i, w in enumerate(self.vocab)}
        self.bits = int(z["bits"][0])
        self.uni_lp = z["uni_lp"].astype(np.float64).tolist()
        self.uni_bo = z["uni_bo"].astype(np.float64).tolist()
        self.bi_keys, self.bi_lp, self.bi_bo = z["bi_keys"], z["bi_lp"], z["bi_bo"]
        self.tri_keys, self.tri_lp = z["tri_keys"], z["tri_lp"]
        self._sorted_words = sorted(self.vocab[3:])
        self.char = CharLM(z) if "ch_meta" in z.files else None
        self._prefix_cache: dict = {}

    @staticmethod
    def _find(keys: np.ndarray, k: int) -> int:
        j = int(np.searchsorted(keys, k))
        return j if j < len(keys) and int(keys[j]) == k else -1

    def logp(self, u: int, v: int, w: int) -> float:
        """log p(w | u v); u = -1 means only one word of context (sentence start)."""
        b = self.bits
        if u >= 0:
            j = self._find(self.tri_keys, (u << (2 * b)) | (v << b) | w)
            if j >= 0:
                return float(self.tri_lp[j])
            j = self._find(self.bi_keys, (u << b) | v)
            bo = float(self.bi_bo[j]) if j >= 0 else 0.0
        else:
            bo = 0.0
        j = self._find(self.bi_keys, (v << b) | w)
        if j >= 0:
            return bo + float(self.bi_lp[j])
        return bo + self.uni_bo[v] + self.uni_lp[w]

    def is_prefix(self, s: str) -> bool:
        r = self._prefix_cache.get(s)
        if r is None:
            i = bisect.bisect_left(self._sorted_words, s)
            r = i < len(self._sorted_words) and self._sorted_words[i].startswith(s)
            if len(self._prefix_cache) > 200000:
                self._prefix_cache.clear()
            self._prefix_cache[s] = r
        return r


class CharLM:
    """Character n-gram spelling model for out-of-vocabulary words (ch_* arrays in the LM npz)."""

    def __init__(self, z):
        chars = bytes(z["ch_chars"]).decode("utf-8")
        self.cid = {c: i + 3 for i, c in enumerate(chars)}
        self.order, self.cb = (int(v) for v in z["ch_meta"])
        self.k = [None] + [z[f"ch_k{n}"] for n in range(1, self.order + 1)]
        self.p = [None] + [z[f"ch_p{n}"].astype(np.float64) for n in range(1, self.order + 1)]
        self.ck = [None] + [z[f"ch_ctx{n}"] for n in range(1, self.order + 1)]
        self.g = [None] + [z[f"ch_g{n}"].astype(np.float64) for n in range(1, self.order + 1)]
        self.floor = math.log(1.0 / (len(chars) + 2)) - 3.0  # unseen character
        self._cache: dict = {}

    def _lp(self, hist: tuple, c: int) -> float:
        bo = 0.0
        for n in range(self.order, 0, -1):
            h = hist[len(hist) - (n - 1):] if n > 1 else ()
            k = 0
            for x in h:
                k = (k << self.cb) | x
            kk = (k << self.cb) | c
            j = NgramLM._find(self.k[n], kk)
            if j >= 0:
                return bo + float(self.p[n][j])
            if n > 1:
                j = NgramLM._find(self.ck[n], k)
                if j >= 0:
                    bo += float(self.g[n][j])
        return bo + self.floor

    def score(self, word: str, final: bool) -> float:
        """log p(spelling); final=True adds the end-of-word symbol (prefix score otherwise)."""
        key = (word, final)
        r = self._cache.get(key)
        if r is not None:
            return r
        if len(word) > 1:
            r = self.score(word[:-1], False)
        else:
            r = 0.0
        ids = [1] * (self.order - 1) + [self.cid.get(c, 0) for c in word]
        if word:
            r += self._lp(tuple(ids[-self.order:-1]), ids[-1])
        if final:
            r += self._lp(tuple(ids[-(self.order - 1):]), 2)
        if len(self._cache) > 300000:
            self._cache.clear()
        self._cache[key] = r
        return r


class LmDecoder:
    def __init__(self, lm_path, vocab, alpha: float = 0.5, beta: float = 1.0, beam: int = 16,
                 unk_penalty: float = 0.0, partial_penalty: float = -6.0, token_min_logp: float = -7.0,
                 beam_prune: float = 12.0, blank_skip: float = 0.999, char_weight: float = 0.5,
                 char_bonus: float = 0.0, lm=None, lm_weights=None):
        """lm_path: one .npz path, or a list of paths combined log-linearly with lm_weights (default
        equal weights summing to 1); the first LM supplies the OOV char model and <unk> decision.
        lm: already-loaded NgramLM or list of them (shares memory between decoders)."""
        if isinstance(vocab, str):
            vocab = json.load(open(vocab, encoding="utf-8"))
        if lm is None:
            paths = [lm_path] if isinstance(lm_path, str) else list(lm_path)
            lm = [NgramLM(p) for p in paths]
        self.lms = list(lm) if isinstance(lm, (list, tuple)) else [lm]
        self.lm = self.lms[0]
        self.lm_weights = list(lm_weights) if lm_weights else [1.0 / len(self.lms)] * len(self.lms)
        self.alpha, self.beta, self.beam = alpha, beta, beam
        self.unk_penalty, self.partial_penalty = unk_penalty, partial_penalty
        self.token_min_logp, self.beam_prune = token_min_logp, beam_prune
        self.blank_skip = math.log(blank_skip)
        self.char_weight = char_weight
        self.char_bonus = char_bonus  # per character of every scored word: counters deletions
        self.nv = max(vocab.values()) + 1
        self.blank = vocab["[PAD]"]
        id2tok = {i: t for t, i in vocab.items()}
        # kind: 0 = ignore (blank / special), 1 = word char, 2 = space delimiter, 3 = punctuation delimiter
        self.kind = np.zeros(self.nv, np.int8)
        self.text = [""] * self.nv
        for i in range(self.nv):
            t = id2tok.get(i, "")
            if i == self.blank or not t or (t.startswith("[") and t.endswith("]")) or t in ("<s>", "</s>"):
                continue
            if t == "|":
                self.kind[i], self.text[i] = 2, " "
            elif _is_word_char(t):
                self.kind[i], self.text[i] = 1, t.lower()
            else:
                self.kind[i], self.text[i] = 3, t
        self.usable = self.kind > 0
        self._wcache: dict = {}

    # --- LM helpers ---------------------------------------------------------------------------
    def _word(self, ctx: tuple, word: str):
        """-> (score increment incl. alpha/beta/unk, new context)."""
        key = (ctx, word)
        r = self._wcache.get(key)
        if r is None:
            lp, nctx = 0.0, []
            for m, wt, (u, v) in zip(self.lms, self.lm_weights, ctx):
                w = m.wid.get(word, UNK)
                x = m.logp(u, v, w)
                if w == UNK and m.char is not None:
                    x += self.char_weight * m.char.score(word, True)
                lp += wt * x
                nctx.append((v, w))
            if self.lm.wid.get(word, UNK) == UNK:
                lp += self.unk_penalty
            s = self.alpha * lp + self.beta + self.char_bonus * len(word)
            r = (s, tuple(nctx))
            if len(self._wcache) > 500000:
                self._wcache.clear()
            self._wcache[key] = r
        return r

    def _partial(self, partial: str) -> float:
        if not partial:
            return 0.0
        bonus = self.char_bonus * len(partial)
        if any(m.is_prefix(partial) for m in self.lms):
            return bonus
        if self.lm.char is not None:
            return bonus + self.alpha * (self.char_weight * self.lm.char.score(partial, False) + self.partial_penalty)
        return bonus + self.partial_penalty

    # --- search ------------------------------------------------------------------------------
    def decode(self, logits: np.ndarray, log_probs: bool = False) -> str:
        x = np.asarray(logits, dtype=np.float32)[:, : self.nv]
        if not log_probs:
            x = x - x.max(-1, keepdims=True)
            x = x - np.log(np.exp(x).sum(-1, keepdims=True))
        blank = self.blank
        kind, text = self.kind, self.text
        # beam: key = display text -> [p_blank, p_nonblank, last_token, partial_word, ctx, lm_score]
        beams = {"": [0.0, NEG, -1, "", tuple((-1, BOS) for _ in self.lms), 0.0]}
        masked = np.where(self.usable, x, NEG)
        for t in range(x.shape[0]):
            row = x[t]
            lb = float(row[blank])
            if lb > self.blank_skip:  # (near-)certain blank frame: every beam just absorbs it
                for b in beams.values():
                    b[0] = _lse(b[0], b[1]) + lb
                    b[1] = NEG
                continue
            mrow = masked[t]
            cand = np.flatnonzero(mrow >= self.token_min_logp)
            if cand.size == 0:
                cand = np.array([int(mrow.argmax())])
            cvals = mrow[cand].tolist()
            cand = cand.tolist()
            new: dict = {}
            for key, b in beams.items():
                pb, pnb, last, partial, ctx, lms = b
                tot = _lse(pb, pnb)
                nb = new.get(key)
                if nb is None:
                    nb = new[key] = [NEG, NEG, last, partial, ctx, lms]
                nb[0] = _lse(nb[0], tot + lb)
                if last >= 0 and pnb > NEG:  # repeated last token collapses onto the same prefix
                    i = cand.index(last) if last in cand else -1
                    if i >= 0:
                        nb[1] = _lse(nb[1], pnb + cvals[i])
                for c, lc in zip(cand, cvals):
                    src = (pb if c == last else tot) + lc
                    k = kind[c]
                    if k == 1:
                        nkey, npartial, nctx, nlms = key + text[c], partial + text[c], ctx, lms
                    else:
                        if k == 2 and (not key or key[-1] == " "):
                            nb[0] = _lse(nb[0], src)  # redundant space: same prefix, like a blank
                            continue
                        if partial:
                            inc, nctx = self._word(ctx, partial)
                            nlms = lms + inc
                        else:
                            nctx, nlms = ctx, lms
                        npartial = ""
                        nkey = key + text[c]
                    e = new.get(nkey)
                    if e is None:
                        new[nkey] = [NEG, src, c, npartial, nctx, nlms]
                    else:
                        e[1] = _lse(e[1], src)
            # prune
            scored = sorted(((_lse(v[0], v[1]) + v[5] + self._partial(v[3]), k) for k, v in new.items()), reverse=True)
            best = scored[0][0]
            beams = {k: new[k] for s, k in scored[: self.beam] if s >= best - self.beam_prune}
        # finalise: score unfinished word and end of sentence
        best_s, best_k = NEG, ""
        for key, (pb, pnb, last, partial, ctx, lms) in beams.items():
            s = _lse(pb, pnb) + lms
            if partial:
                inc, ctx = self._word(ctx, partial)
                s += inc
            if ctx[0][1] != BOS:
                s += self.alpha * sum(wt * m.logp(u, v, EOS) for m, wt, (u, v) in zip(self.lms, self.lm_weights, ctx))
            if s > best_s:
                best_s, best_k = s, key
        return " ".join(best_k.split())
