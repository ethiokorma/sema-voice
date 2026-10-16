// w2v-BERT 2.0 front-end (port of SeamlessM4TFeatureExtractor, same as Kotlin W2vBertFeatureExtractor)
// and greedy CTC decoder. Verified against the Python reference fixture by web/test/ctc-test.mjs.
const FRAME = 400, HOP = 160, NFFT = 512, NBINS = 257, MELS = 80, STRIDE = 2;

const WINDOW = (() => { const w = new Float64Array(FRAME); for (let n = 0; n < FRAME; n++) w[n] = Math.pow(0.5 - 0.5 * Math.cos((2 * Math.PI * n) / (FRAME - 1)), 0.85); return w; })();
const MEL = (() => {
  const mel = (f) => 1127 * Math.log(1 + f / 700);
  const lo = mel(20), hi = mel(8000), d = (hi - lo) / (MELS + 1);
  const bank = [];
  for (let m = 0; m < MELS; m++) {
    const l = lo + m * d, c = lo + (m + 1) * d, r = lo + (m + 2) * d, row = new Float64Array(NBINS);
    for (let k = 0; k < NBINS; k++) { const mm = mel((k * 16000) / NFFT); row[k] = Math.max(0, Math.min((mm - l) / (c - l), (r - mm) / (r - c))); }
    bank.push(row);
  }
  return bank;
})();

function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1; for (; j & bit; bit >>= 1) j ^= bit; j ^= bit;
    if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (-2 * Math.PI) / len, wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const a = i + k, b = a + len / 2;
        const vr = re[b] * cr - im[b] * ci, vi = re[b] * ci + im[b] * cr;
        re[b] = re[a] - vr; im[b] = im[a] - vi; re[a] += vr; im[a] += vi;
        const nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
      }
    }
  }
}

/** @param {Float32Array} samples 16 kHz mono in [-1,1] @returns {{data: Float32Array, frames: number, dim: number}} */
export function extractFeatures(samples) {
  const T = samples.length < FRAME ? 0 : 1 + Math.floor((samples.length - FRAME) / HOP);
  const logMel = new Float64Array(T * MELS);
  const re = new Float64Array(NFFT), im = new Float64Array(NFFT), fr = new Float64Array(FRAME);
  for (let t = 0; t < T; t++) {
    const off = t * HOP; let mean = 0;
    for (let i = 0; i < FRAME; i++) { fr[i] = samples[off + i] * 32768; mean += fr[i]; }
    mean /= FRAME;
    for (let i = 0; i < FRAME; i++) fr[i] -= mean;
    for (let i = FRAME - 1; i >= 1; i--) fr[i] -= 0.97 * fr[i - 1];
    fr[0] *= 1 - 0.97;
    re.fill(0); im.fill(0);
    for (let i = 0; i < FRAME; i++) re[i] = fr[i] * WINDOW[i];
    fft(re, im);
    for (let m = 0; m < MELS; m++) {
      const w = MEL[m]; let acc = 0;
      for (let k = 0; k < NBINS; k++) if (w[k] !== 0) acc += w[k] * (re[k] * re[k] + im[k] * im[k]);
      logMel[t * MELS + m] = Math.log(Math.max(acc, 1.192092955078125e-7));
    }
  }
  if (T > 1) for (let m = 0; m < MELS; m++) {
    let mean = 0; for (let t = 0; t < T; t++) mean += logMel[t * MELS + m]; mean /= T;
    let v = 0; for (let t = 0; t < T; t++) { const d = logMel[t * MELS + m] - mean; v += d * d; }
    const std = Math.sqrt(v / (T - 1) + 1e-7);
    for (let t = 0; t < T; t++) logMel[t * MELS + m] = (logMel[t * MELS + m] - mean) / std;
  }
  const F = Math.floor(T / STRIDE), dim = MELS * STRIDE, data = new Float32Array(F * dim);
  for (let t = 0; t < F; t++) for (let s = 0; s < STRIDE; s++) for (let m = 0; m < MELS; m++) data[t * dim + s * MELS + m] = logMel[(t * STRIDE + s) * MELS + m];
  return { data, frames: F, dim };
}

/** Greedy CTC: argmax per frame, collapse repeats, drop blank and [TAG] tokens, "|" -> space. */
export function ctcDecode(logits, frames, vocabSize, idToToken, blankId, delimiter = '|') {
  let out = '', prev = -1;
  for (let t = 0; t < frames; t++) {
    let best = 0, bv = -Infinity; const base = t * vocabSize;
    for (let v = 0; v < vocabSize; v++) { const x = logits[base + v]; if (x > bv) { bv = x; best = v; } }
    if (best !== prev && best !== blankId) {
      const tok = idToToken[best];
      if (tok !== undefined && !(tok.startsWith('[') && tok.endsWith(']')) && tok !== '<s>' && tok !== '</s>') out += tok === delimiter ? ' ' : tok;
    }
    prev = best;
  }
  return out.replace(/\s+/g, ' ').trim();
}
