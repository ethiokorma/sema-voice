// Sema Voice PWA — everything runs in the browser (transformers.js + ONNX Runtime Web).
// No server, no account, no analytics. Models are cached by transformers.js in the Cache API.
import { pipeline, env } from 'https://cdn.jsdelivr.net/npm/@xenova/transformers@2.17.2';

env.allowLocalModels = true;
env.allowRemoteModels = true;
env.localModelPath = new URL('./models/', location.href).href;
env.useBrowserCache = true;

const $ = (id) => document.getElementById(id);
const ui = { mic: $('mic'), status: $('status'), level: $('level'), progress: $('progress'), cancel: $('cancel'), result: $('result'),
  text: $('text'), stats: $('stats'), trLabel: $('trLabel'), translation: $('translation'), trError: $('trError'),
  copy: $('copy'), copyTr: $('copyTr'), share: $('share'), translate: $('translate'), speak: $('speak'), download: $('download'),
  discard: $('discard'), inLang: $('inLang'), outLang: $('outLang'), mode: $('mode'), uiLang: $('uiLang'), modelStatus: $('modelStatus'), warm: $('warm'), install: $('install') };

const STRINGS = {
  en: { tagline: 'Speak in your language. Write in any language.', input: 'Input', output: 'Output', mode: 'Mode', modeExact: 'Exact', modeClean: 'Clean', modeTranslate: 'Translate',
    tap: 'Tap the microphone and speak.', cancel: 'Cancel', transcript: 'Transcript', translation: 'Translation', copy: 'Copy', copyTr: 'Copy translation', share: 'Share',
    translate: 'Translate', listen: 'Listen', save: 'Save .txt', discard: 'Discard', models: 'Models & privacy', warm: 'Download models for current languages', install: 'Install app',
    privacy: 'Everything runs inside this browser tab (whisper + translation via transformers.js, ONNX Runtime Web). Audio never leaves your device. Models (30–115 MB) are downloaded once and cached for offline use.',
    kbnote: 'Browsers cannot provide a system keyboard. Use the Android app for keyboard dictation inside other apps; here, copy or share the text.',
    listening: 'Listening… tap to stop', loading: 'Loading model', transcribing: 'Transcribing on this device…', translating: 'Translating on this device…', empty: 'Nothing was heard. Try again.',
    micDenied: 'Microphone permission is needed.', noTr: 'No translation model for this pair.', copied: 'Copied', local: 'processed on this device', amNote: 'Amharic model (experimental fine-tune)' },
  am: { tagline: 'በቋንቋዎ ይናገሩ። በማንኛውም ቋንቋ ይጻፉ።', input: 'ግብዓት', output: 'ውጤት', mode: 'ሁነታ', modeExact: 'ልክ', modeClean: 'ንጹህ', modeTranslate: 'ተርጉም',
    tap: 'ማይክሮፎኑን ነክተው ይናገሩ።', cancel: 'ሰርዝ', transcript: 'ግልባጭ', translation: 'ትርጉም', copy: 'ቅዳ', copyTr: 'ትርጉሙን ቅዳ', share: 'አጋራ',
    translate: 'ተርጉም', listen: 'አዳምጥ', save: '.txt አስቀምጥ', discard: 'አስወግድ', models: 'ሞዴሎች እና ግላዊነት', warm: 'ለአሁኑ ቋንቋዎች ሞዴሎችን አውርድ', install: 'መተግበሪያውን ጫን',
    privacy: 'ሁሉም ነገር በዚህ የአሳሽ ትር ውስጥ ይሰራል። ድምጽ ከመሣሪያዎ አይወጣም። ሞዴሎች (30–115 MB) አንድ ጊዜ ወርደው ከመስመር ውጭ ለመጠቀም ይቀመጣሉ።',
    kbnote: 'አሳሾች የስርዓት ኪቦርድ መስጠት አይችሉም። በሌሎች መተግበሪያዎች ውስጥ ለመጻፍ የአንድሮይድ መተግበሪያውን ይጠቀሙ፤ እዚህ ጽሑፉን ይቅዱ ወይም ያጋሩ።',
    listening: 'እያዳመጠ ነው… ለማቆም ይንኩ', loading: 'ሞዴል እየተጫነ ነው', transcribing: 'በዚህ መሣሪያ ላይ እየተጻፈ ነው…', translating: 'በዚህ መሣሪያ ላይ እየተተረጎመ ነው…', empty: 'ምንም አልተሰማም። እንደገና ይሞክሩ።',
    micDenied: 'የማይክሮፎን ፈቃድ ያስፈልጋል።', noTr: 'ለዚህ ጥንድ የትርጉም ሞዴል የለም።', copied: 'ተቀድቷል', local: 'በዚህ መሣሪያ ላይ ተሰርቷል', amNote: 'የአማርኛ ሞዴል (የሙከራ)' },
};
let L = STRINGS.en;
function applyLang(code) {
  L = STRINGS[code] || STRINGS.en; document.documentElement.lang = code; localStorage.setItem('ui', code);
  document.querySelectorAll('[data-i18n]').forEach((el) => { const k = el.dataset.i18n; if (L[k]) el.textContent = L[k]; });
}

// ---- model selection ---------------------------------------------------------------------
// Amharic: local ONNX export of b1n1yam/shook-tiny-amharic-600hr (Apache-2.0), see web/models/.
// Other languages: Xenova/whisper-tiny (MIT) — stock Whisper is unusable for Amharic (WER > 100 %).
const SPEECH = { am: 'whisper-tiny-amharic', en: 'Xenova/whisper-tiny', ar: 'Xenova/whisper-tiny', so: 'Xenova/whisper-tiny' };
const WHISPER_LANG = { am: 'amharic', en: 'english', ar: 'arabic', so: 'somali' };
const MARIAN_TARGET = { am: '>>amh<<', ar: '>>ara<<', so: '>>som<<', ti: '>>tir<<' };
function translationModel(from, to) {
  if (from === to) return null;
  if (to === 'en') return { id: 'Xenova/opus-mt-mul-en', prefix: '' };            // am/ar/so/ti -> en (Apache-2.0)
  if (from === 'en' && MARIAN_TARGET[to]) return { id: 'Xenova/opus-mt-en-mul', prefix: MARIAN_TARGET[to] + ' ' };
  return null; // e.g. am -> ar would need M2M100 (630 MB); not offered in the PWA
}

const pipes = new Map();
async function getPipe(task, model) {
  const key = task + '|' + model;
  if (!pipes.has(key)) {
    ui.progress.hidden = false; ui.progress.value = 0;
    const p = pipeline(task, model, { quantized: true, progress_callback: (e) => { if (e.status === 'progress') { ui.progress.value = e.progress || 0; ui.status.textContent = `${L.loading} ${e.file || ''} ${Math.round(e.progress || 0)}%`; } } });
    pipes.set(key, p);
    try { await p; } catch (err) { pipes.delete(key); throw err; } finally { ui.progress.hidden = true; }
  }
  return pipes.get(key);
}

// ---- recording ---------------------------------------------------------------------------
let mediaRecorder = null, chunks = [], audioCtx = null, analyser = null, levelTimer = null, stream = null, cancelled = false;

async function startRecording() {
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } }); }
  catch { ui.status.textContent = L.micDenied; return; }
  chunks = []; cancelled = false;
  mediaRecorder = new MediaRecorder(stream);
  mediaRecorder.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
  mediaRecorder.onstop = onStopped;
  mediaRecorder.start(250);
  audioCtx = new AudioContext();
  analyser = audioCtx.createAnalyser(); analyser.fftSize = 512;
  audioCtx.createMediaStreamSource(stream).connect(analyser);
  const buf = new Uint8Array(analyser.fftSize);
  levelTimer = setInterval(() => { analyser.getByteTimeDomainData(buf); let s = 0; for (const v of buf) { const d = (v - 128) / 128; s += d * d; } ui.level.style.width = Math.min(100, Math.sqrt(s / buf.length) * 400) + '%'; }, 80);
  ui.mic.classList.add('rec'); ui.mic.textContent = '⏹'; ui.mic.setAttribute('aria-label', 'Stop'); ui.status.textContent = L.listening; ui.cancel.hidden = false;
  setTimeout(() => { if (mediaRecorder?.state === 'recording') mediaRecorder.stop(); }, 120000); // max 2 min
}

function stopRecording() { if (mediaRecorder?.state === 'recording') mediaRecorder.stop(); }

function teardown() {
  clearInterval(levelTimer); ui.level.style.width = '0';
  stream?.getTracks().forEach((t) => t.stop()); stream = null;
  audioCtx?.close(); audioCtx = null;
  ui.mic.classList.remove('rec'); ui.mic.textContent = '🎤'; ui.mic.setAttribute('aria-label', 'Start dictation'); ui.cancel.hidden = true;
}

async function onStopped() {
  teardown();
  if (cancelled) { ui.status.textContent = L.tap; return; }
  const blob = new Blob(chunks, { type: mediaRecorder.mimeType });
  if (blob.size < 2000) { ui.status.textContent = L.empty; return; }
  try {
    const pcm = await decodeTo16k(blob);
    if (pcm.length < 16000 * 0.3) { ui.status.textContent = L.empty; return; }
    await transcribe(pcm);
  } catch (err) { console.error(err); ui.status.textContent = String(err.message || err); }
}

async function decodeTo16k(blob) {
  const ctx = new OfflineAudioContext(1, 1, 16000);
  const decoded = await ctx.decodeAudioData(await blob.arrayBuffer());
  const off = new OfflineAudioContext(1, Math.ceil(decoded.duration * 16000), 16000);
  const src = off.createBufferSource(); src.buffer = decoded; src.connect(off.destination); src.start();
  const out = await off.startRendering();
  return out.getChannelData(0);
}

// ---- transcription -----------------------------------------------------------------------
let lastAudioMs = 0;
async function transcribe(pcm) {
  const lang = ui.inLang.value;
  ui.status.textContent = `${L.loading}…`;
  const asr = await getPipe('automatic-speech-recognition', SPEECH[lang]);
  ui.status.textContent = L.transcribing;
  const t0 = performance.now();
  const out = await asr(pcm, { language: WHISPER_LANG[lang], task: 'transcribe', chunk_length_s: 30, stride_length_s: 5 });
  const ms = Math.round(performance.now() - t0);
  lastAudioMs = Math.round(pcm.length / 16);
  let text = (out.text || '').trim().replace(/\s+/g, ' ');
  if (ui.mode.value !== 'exact') text = clean(text, lang);
  if (!text) { ui.status.textContent = L.empty; return; }
  ui.text.value = text;
  ui.stats.textContent = `${ms} ms · RTF ${(ms / lastAudioMs).toFixed(2)} · ${SPEECH[lang]} · ${L.local}`;
  ui.result.hidden = false; ui.translation.hidden = true; ui.trLabel.hidden = true; ui.copyTr.hidden = true; ui.trError.hidden = true;
  ui.status.textContent = L.tap;
  if (ui.mode.value === 'translate') await doTranslate();
}

/** Deterministic cleanup, same rules as the Android RuleBasedWritingAssistant (no meaning changes). */
function clean(text, lang) {
  const fillers = lang === 'am' ? ['እ', 'እም', 'ኧ', 'um', 'uh', 'uhm', 'erm', 'hmm'] : ['um', 'uh', 'uhm', 'erm', 'hmm'];
  let t = text;
  for (const f of fillers) t = t.replace(new RegExp(`(?:,\\s*)?(?<![\\p{L}\\p{M}])${f}(?![\\p{L}\\p{M}])[,\\s]*`, 'giu'), ' ');
  t = t.replace(/\s+([,.;:?!።፣፤፥፧])/g, '$1').replace(/\s+/g, ' ').trim();
  const geez = /[ሀ-፿]/.test(t) && (t.match(/[ሀ-፿]/g) || []).length > (t.match(/[A-Za-z]/g) || []).length;
  if (geez) t = t.replace(/\.(?=\s|$)/g, '።').replace(/\?(?=\s|$)/g, '፧').replace(/,(?=\s)/g, '፣');
  if (t && /[\p{L}\p{N}]$/u.test(t)) t += geez ? '።' : '.';
  if (!geez) t = t.replace(/(^|[.!?]\s+)([a-z])/g, (m, a, b) => a + b.toUpperCase());
  return t;
}

async function doTranslate() {
  const from = ui.inLang.value, to = ui.outLang.value, text = ui.text.value.trim();
  if (!text) return;
  const m = translationModel(from, to);
  ui.trError.hidden = true;
  if (!m) { ui.trError.textContent = L.noTr; ui.trError.hidden = false; return; }
  ui.status.textContent = `${L.loading}…`;
  const tr = await getPipe('translation', m.id);
  ui.status.textContent = L.translating;
  const t0 = performance.now();
  const sentences = text.split(/(?<=[.!?።፧])\s+/).filter(Boolean);
  const parts = [];
  for (const s of sentences) { const r = await tr(m.prefix + s, { max_new_tokens: 200 }); parts.push((r[0]?.translation_text || '').trim()); }
  ui.translation.textContent = parts.join(' ');
  ui.translation.hidden = false; ui.trLabel.hidden = false; ui.copyTr.hidden = false;
  ui.status.textContent = `${Math.round(performance.now() - t0)} ms · ${m.id} · ${L.local}`;
}

// ---- actions -----------------------------------------------------------------------------
ui.mic.onclick = () => (mediaRecorder?.state === 'recording' ? stopRecording() : startRecording());
ui.cancel.onclick = () => { cancelled = true; stopRecording(); };
ui.copy.onclick = async () => { await navigator.clipboard.writeText(ui.text.value); ui.status.textContent = L.copied; };
ui.copyTr.onclick = async () => { await navigator.clipboard.writeText(ui.translation.textContent); ui.status.textContent = L.copied; };
ui.share.onclick = async () => { const text = ui.translation.hidden ? ui.text.value : `${ui.text.value}\n${ui.translation.textContent}`; if (navigator.share) await navigator.share({ text }); else { await navigator.clipboard.writeText(text); ui.status.textContent = L.copied; } };
ui.translate.onclick = () => doTranslate().catch((e) => { ui.trError.textContent = String(e.message || e); ui.trError.hidden = false; });
ui.discard.onclick = () => { ui.result.hidden = true; ui.text.value = ''; };
ui.download.onclick = () => { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([ui.text.value + (ui.translation.hidden ? '' : '\n\n' + ui.translation.textContent)], { type: 'text/plain' })); a.download = 'sema-voice.txt'; a.click(); };
ui.speak.onclick = () => {
  const text = ui.translation.hidden ? ui.text.value : ui.translation.textContent;
  const lang = ui.translation.hidden ? ui.inLang.value : ui.outLang.value;
  const voices = speechSynthesis.getVoices().filter((v) => v.lang.toLowerCase().startsWith(lang));
  if (!voices.length) { ui.status.textContent = `No system voice for "${lang}" in this browser.`; return; }
  const u = new SpeechSynthesisUtterance(text); u.voice = voices[0]; u.lang = voices[0].lang; speechSynthesis.speak(u);
};
ui.warm.onclick = async () => {
  try {
    await getPipe('automatic-speech-recognition', SPEECH[ui.inLang.value]);
    const m = translationModel(ui.inLang.value, ui.outLang.value); if (m) await getPipe('translation', m.id);
    ui.modelStatus.textContent = 'Models cached for offline use.';
  } catch (e) { ui.modelStatus.textContent = String(e.message || e); }
};
ui.inLang.onchange = () => { localStorage.setItem('in', ui.inLang.value); ui.modelStatus.textContent = ui.inLang.value === 'am' ? L.amNote : ''; };
ui.outLang.onchange = () => localStorage.setItem('out', ui.outLang.value);
ui.mode.onchange = () => localStorage.setItem('mode', ui.mode.value);
ui.uiLang.onchange = () => applyLang(ui.uiLang.value);

// ---- init ---------------------------------------------------------------------------------
ui.inLang.value = localStorage.getItem('in') || 'am';
ui.outLang.value = localStorage.getItem('out') || 'en';
ui.mode.value = localStorage.getItem('mode') || 'clean';
ui.uiLang.value = localStorage.getItem('ui') || (navigator.language.startsWith('am') ? 'am' : 'en');
applyLang(ui.uiLang.value);
ui.inLang.onchange();
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js');
let installEvent = null;
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installEvent = e; ui.install.hidden = false; });
ui.install.onclick = () => installEvent?.prompt();
