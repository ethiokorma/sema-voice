// JS port of core/common/RepetitionGuard.kt (same rules as tests/ai-quality/repetition_guard.py).
// Measured on 30 FLEURS Amharic clips with the tiny Whisper model: WER 0.812 -> 0.618.
function lev(a, b) {
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) cur.push(Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)));
    prev = cur;
  }
  return prev[b.length];
}

function restartCut(words, minSim = 0.6) {
  if (words.length < 6) return null;
  let best = 0, bestK = null;
  for (let k = Math.max(3, Math.floor(words.length * 0.25)); k <= Math.floor(words.length * 0.75); k++) {
    const a = words.slice(0, k).join(' '), b = words.slice(k).join(' ');
    const n = Math.min(a.length, b.length);
    if (!n) continue;
    const s = 1 - lev([...a.slice(0, n)], [...b.slice(0, n)]) / n;
    if (s > best) { best = s; bestK = k; }
  }
  return best >= minSim ? bestK : null;
}

/** Returns {text, loop, tooLong, needsReview}. Only pathological tails are removed. */
export function guard(text, audioMs = 0, minRepeats = 3) {
  let words = text.trim().split(/\s+/).filter(Boolean);
  let loop = false;
  const cut = restartCut(words);
  if (cut !== null) { words = words.slice(0, cut); loop = true; }
  let changed = true;
  while (changed && words.length >= minRepeats) {
    changed = false;
    for (let n = Math.min(6, Math.floor(words.length / minRepeats)); n >= 1; n--) {
      const unit = words.slice(-n).join('\u0001');
      let reps = 1, end = words.length - n;
      while (end - n >= 0 && words.slice(end - n, end).join('\u0001') === unit) { reps++; end -= n; }
      if (reps >= minRepeats) { loop = true; words = words.slice(0, end + n); changed = true; break; }
    }
  }
  let out = words.join(' ');
  if (/(.)\1{7,}/u.test(out)) { loop = true; out = out.replace(/(.)\1{7,}/gu, (m) => [...m].slice(0, 2).join('')); }
  const chars = out.replace(/\s/g, '').length;
  const tooLong = audioMs > 0 && chars > 20 && chars > (audioMs / 1000) * 12;
  return { text: out, loop, tooLong, needsReview: loop || tooLong };
}
