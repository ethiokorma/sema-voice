// Sema Voice inference worker: loads and runs transformers.js models off the page thread, so a
// slow or failing model can never freeze or kill the UI. Messages:
//   in : {id, type:'asr', model, samples(Float32Array, transferred), language}
//        {id, type:'translate', model, text, prefix}
//        {id, type:'warm', task, model}   {type:'dispose'}
//   out: {id, type:'progress', file, progress, loaded, total}  {id, type:'status', stage}
//        {id, type:'result', text, ms}  {id, type:'error', message}
import { pipeline, env } from 'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.3.3';

env.allowLocalModels = true;
env.allowRemoteModels = true;
env.localModelPath = new URL('./models/', self.location.href).href;
env.useBrowserCache = true;
// GitHub Pages has no COOP/COEP headers, so WASM threads are unavailable; be explicit.
env.backends.onnx.wasm.numThreads = 1;

const loaded = { asr: null, translation: null }; // {model, pipe}
const inflight = new Map(); // model -> Promise<pipe>; prevents loading the same model twice

async function get(task, model, id) {
  const slot = task === 'automatic-speech-recognition' ? 'asr' : 'translation';
  if (loaded[slot]?.model === model) return loaded[slot].pipe;
  if (inflight.has(model)) return inflight.get(model);
  const p = load(task, model, id, slot).finally(() => inflight.delete(model));
  inflight.set(model, p);
  return p;
}

async function load(task, model, id, slot) {
  // Keep memory bounded on phones: only one model per task, and drop the translation model
  // before loading a speech model (speech is the critical path).
  if (loaded[slot]) { await loaded[slot].pipe.dispose?.(); loaded[slot] = null; }
  if (slot === 'asr' && loaded.translation) { await loaded.translation.pipe.dispose?.(); loaded.translation = null; }
  postMessage({ id, type: 'status', stage: 'loading' });
  const pipe = await pipeline(task, model, {
    dtype: 'q8', device: 'wasm',
    progress_callback: (e) => {
      if (e.status === 'progress') postMessage({ id, type: 'progress', file: e.file, progress: e.progress || 0, loaded: e.loaded, total: e.total });
    },
  });
  loaded[slot] = { model, pipe };
  return pipe;
}

// ---- "Best accuracy" engine: Ethio-ASR w2v-BERT CTC via onnxruntime-web (desktop / high-RAM) ----
// The model is split into <100 MB chunks (GitHub Pages limit), downloaded once and kept in the
// Cache API. Feature extraction and CTC decoding are the verified ports in ctc.js.
import { extractFeatures, ctcDecode } from './ctc.js';
const ORT_VER = '1.22.0';
let ort = null, ortGpu = false, ctcSession = null, ctcMeta = null, ctcLoading = null;

async function loadCtc(id, manifestUrl) {
  if (ctcSession) return;
  if (ctcLoading) return ctcLoading;
  ctcLoading = (async () => {
    postMessage({ id, type: 'status', stage: 'loading' });
    if (!ort) {
      // WebGPU is disabled: in testing (headless Chrome, 2026-10-15) the WebGPU path ran 2x faster but
      // produced garbage output for this model. Re-enable only after verifying on real GPUs.
      const gpu = false;
      ort = await import(`https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VER}/dist/${gpu ? 'ort.webgpu.min.mjs' : 'ort.wasm.min.mjs'}`);
      ortGpu = gpu;
      ort.env.wasm.wasmPaths = `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VER}/dist/`;
      // Threads need cross-origin isolation (provided by sw.js); fall back to 1 thread otherwise.
      ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.min(8, navigator.hardwareConcurrency || 4) : 1;
    }
    const base = new URL(manifestUrl, self.location.href);
    const manifest = await (await fetch(base)).json();
    const cache = await caches.open('sema-ctc-' + manifest.version);
    const total = manifest.chunks.reduce((a, c) => a + c.size, 0);
    const model = new Uint8Array(total);
    let done = 0, off = 0;
    for (const c of manifest.chunks) {
      const url = new URL(c.file, base).href;
      let resp = await cache.match(url);
      if (!resp) {
        const r = await fetch(url);
        if (!r.ok) throw new Error(`download failed: ${c.file} HTTP ${r.status}`);
        const reader = r.body.getReader(); const parts = []; let got = 0;
        for (;;) { const { done: d, value } = await reader.read(); if (d) break; parts.push(value); got += value.length;
          postMessage({ id, type: 'progress', progress: ((done + got) / total) * 100, loaded: done + got, total }); }
        const blob = new Blob(parts);
        // Caching is best-effort (quota, private mode, duplicate entries must not break loading).
        try { await cache.delete(url); await cache.put(url, new Response(blob)); } catch {}
        resp = new Response(blob);
      }
      const buf = new Uint8Array(await resp.arrayBuffer());
      if (buf.length !== c.size) { await cache.delete(url); throw new Error(`corrupt chunk ${c.file}`); }
      model.set(buf, off); off += buf.length; done += c.size;
      postMessage({ id, type: 'progress', progress: (done / total) * 100, loaded: done, total });
    }
    const vocab = manifest.vocab;
    const idToToken = []; for (const [k, v] of Object.entries(vocab)) idToToken[v] = k;
    ctcMeta = { idToToken, blank: manifest.blank_id, maskType: manifest.mask_type || 'int32', threads: ort.env.wasm.numThreads };
    ctcSession = null;
    if (ortGpu) {
      try { ctcSession = await ort.InferenceSession.create(model, { executionProviders: ['webgpu'], graphOptimizationLevel: 'all' }); ctcMeta.ep = 'webgpu'; }
      catch (e) { ctcSession = null; }
    }
    if (!ctcSession) { ctcSession = await ort.InferenceSession.create(model, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' }); ctcMeta.ep = 'wasm'; }
  })().finally(() => { ctcLoading = null; });
  return ctcLoading;
}

function splitAtSilence(x, maxS, searchS) {
  const SR = 16000, out = [], hop = 800, maxN = maxS * SR, srch = searchS * SR;
  let start = 0;
  while (x.length - start > maxN) {
    const lo = start + maxN - srch, k = Math.floor(srch / hop), e = new Float64Array(k);
    for (let i = 0; i < k; i++) { let a = 0; for (let j = 0; j < hop; j++) { const v = x[lo + i * hop + j]; a += v * v; } e[i] = a; }
    let best = 0, bv = Infinity;
    for (let i = 0; i < k; i++) { let a = 0, c = 0; for (let d = -3; d <= 2; d++) { const q = i + d; if (q >= 0 && q < k) { a += e[q]; c++; } } a /= c; if (a < bv) { bv = a; best = i; } }
    const cut = lo + best * hop + hop / 2;
    out.push(x.subarray(start, cut)); start = cut;
  }
  out.push(x.subarray(start));
  return out;
}

async function runCtc(samples) {
  const out = [];
  // Pieces of at most 20 s (bounds browser memory), cut at the quietest moment rather than at a fixed
  // 20 s mark: fixed cuts split words and cost about 1 WER point on FLEURS.
  for (const chunk of splitAtSilence(samples, 20, 6)) {
    if (chunk.length < 8000) continue;
    const { data, frames, dim } = extractFeatures(chunk);
    if (!frames) continue;
    const feeds = { input_features: new ort.Tensor('float32', data, [1, frames, dim]) };
    if (ctcSession.inputNames.includes('attention_mask')) {
      feeds.attention_mask = ctcMeta.maskType === 'int64'
        ? new ort.Tensor('int64', new BigInt64Array(frames).fill(1n), [1, frames])
        : new ort.Tensor('int32', new Int32Array(frames).fill(1), [1, frames]);
    }
    const res = await ctcSession.run(feeds);
    const logits = res[ctcSession.outputNames[0]];
    out.push(ctcDecode(logits.data, logits.dims[1], logits.dims[2], ctcMeta.idToToken, ctcMeta.blank));
  }
  return out.join(' ').trim();
}

self.onmessage = async ({ data: m }) => {
  try {
    if (m.type === 'ctc') {
      await loadCtc(m.id, m.manifest);
      postMessage({ id: m.id, type: 'status', stage: 'transcribing' });
      const t0 = performance.now();
      const text = await runCtc(m.samples);
      postMessage({ id: m.id, type: 'result', text, ms: Math.round(performance.now() - t0), ep: ctcMeta.ep, threads: ctcMeta.threads });
      return;
    }
    if (m.type === 'warm-ctc') { await loadCtc(m.id, m.manifest); postMessage({ id: m.id, type: 'result', text: '', ms: 0 }); return; }
    if (m.type === 'asr') {
      const asr = await get('automatic-speech-recognition', m.model, m.id);
      postMessage({ id: m.id, type: 'status', stage: 'transcribing' });
      const t0 = performance.now();
      const out = await asr(m.samples, { language: m.language, task: 'transcribe', return_timestamps: false, max_new_tokens: 224 });
      postMessage({ id: m.id, type: 'result', text: (out.text || '').trim(), ms: Math.round(performance.now() - t0) });
    } else if (m.type === 'translate') {
      const tr = await get('translation', m.model, m.id);
      postMessage({ id: m.id, type: 'status', stage: 'translating' });
      const t0 = performance.now();
      const parts = [];
      for (const s of m.text.split(/(?<=[.!?።፧])\s+/).filter(Boolean)) {
        const r = await tr(m.prefix + s, { max_new_tokens: 200 });
        parts.push((r[0]?.translation_text || '').trim());
      }
      postMessage({ id: m.id, type: 'result', text: parts.join(' '), ms: Math.round(performance.now() - t0) });
    } else if (m.type === 'warm') {
      await get(m.task, m.model, m.id);
      postMessage({ id: m.id, type: 'result', text: '', ms: 0 });
    } else if (m.type === 'dispose') {
      for (const k of Object.keys(loaded)) { await loaded[k]?.pipe.dispose?.(); loaded[k] = null; }
    }
  } catch (err) {
    const parts = [];
    if (err?.errors?.length) parts.push(...err.errors.map((e) => e.message || String(e)));
    parts.push(err?.message || String(err));
    postMessage({ id: m.id, type: 'error', message: [...new Set(parts)].join(' | ') });
  }
};
