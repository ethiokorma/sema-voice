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

self.onmessage = async ({ data: m }) => {
  try {
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
