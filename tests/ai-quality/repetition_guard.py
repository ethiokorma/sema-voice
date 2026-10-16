"""Python port of core/common/RepetitionGuard.kt (same rules, so benchmark numbers match the app).

Trims only a looping *tail* (an n-gram, n=1..6, repeated >= 3 times consecutively at the end),
collapses runs of >= 8 identical characters, reports internal loops without removing them, and
flags output with more than 6 words/s + 4. The raw hypothesis is always kept separately.
"""
from __future__ import annotations

import re

_CHAR_RUN = re.compile(r"(.)\1{7,}")


def _internal_loop(words: list[str], min_repeats: int) -> bool:
    for n in range(1, 7):
        i = 0
        while i + n * min_repeats <= len(words):
            unit = words[i:i + n]
            reps, j = 1, i + n
            while j + n <= len(words) and words[j:j + n] == unit:
                reps += 1
                j += n
            if reps >= min_repeats:
                return True
            i += 1
    return False


def _lev(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def restart_cut(words: list[str], min_sim: float = 0.6):
    """Index where the decoder started transcribing the same audio again, or None.
    Compares each candidate suffix (cut between 25 % and 75 %) with an equally long prefix."""
    if len(words) < 6:
        return None
    best_sim, best_k = 0.0, None
    for k in range(max(3, int(len(words) * 0.25)), int(len(words) * 0.75) + 1):
        a, b = " ".join(words[:k]), " ".join(words[k:])
        n = min(len(a), len(b))
        if n == 0:
            continue
        s = 1 - _lev(a[:n], b[:n]) / n
        if s > best_sim:
            best_sim, best_k = s, k
    return best_k if best_sim >= min_sim else None


def check(text: str, audio_ms: int = 0, min_repeats: int = 3) -> dict:
    words = text.split()
    removed, loop = 0, False
    cut = restart_cut(words)
    restart = cut is not None
    if restart:
        removed += len(words) - cut
        words = words[:cut]
    changed = True
    while changed and len(words) >= min_repeats:
        changed = False
        for n in range(min(6, len(words) // min_repeats), 0, -1):
            unit = words[-n:]
            reps, end = 1, len(words) - n
            while end - n >= 0 and words[end - n:end] == unit:
                reps += 1
                end -= n
            if reps >= min_repeats:
                loop = True
                keep = end + n
                removed += len(words) - keep
                words = words[:keep]
                changed = True
                break
    if not loop:
        loop = _internal_loop(words, min_repeats)
    out = " ".join(words)
    if _CHAR_RUN.search(out):
        loop = True
        out = _CHAR_RUN.sub(lambda m: m.group(0)[:2], out)
    # Ge'ez read speech measured at 4.6-9.3 characters/s on FLEURS; > 12/s means duplicated text.
    chars = len(out.replace(" ", ""))
    too_long = audio_ms > 0 and chars > 20 and chars > audio_ms / 1000 * 12.0
    loop = loop or restart
    return {"text": out, "loop": loop, "restart": restart, "too_long": too_long, "removed_words": removed,
            "trimmed": out != text.strip(), "needs_review": loop or too_long}
