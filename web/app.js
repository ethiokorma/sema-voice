// Sema Voice PWA — everything runs in the browser (transformers.js + ONNX Runtime Web).
// No server, no account, no analytics. Models are cached by transformers.js in the Cache API.
// transformers.js v3 (ships a current ONNX Runtime Web; v2.17's runtime rejects the opset-18 export).
// Models run in worker.js so a heavy model can never freeze or crash the page.
import { attachGeezKeyboard } from './keyboard.js';
import { guard } from './guard.js';

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
    micDenied: 'Microphone permission is needed.', noTr: 'No translation model for this pair.', copied: 'Copied', local: 'processed on this device', amNote: 'Amharic model (experimental fine-tune)',
    kb: 'Amharic keyboard', editHint: 'Tap any word in the box to correct it.', space: 'space',
    workerCrash: 'The speech model stopped (likely out of memory). Close other tabs and try again.', review: 'check this text: the model repeated itself',
    engineLocal: 'On-device · private', engineGoogle: 'Google · online, more accurate', googleNote: 'Uses Chrome’s built-in Google speech service: your audio is sent to Google. Free, needs internet.',
    googleUnsupported: 'This browser has no built-in speech recognition. Use Chrome on Android or desktop.', liveListening: 'Listening… words appear as you speak', preparing: 'Preparing model', ready: 'Model ready', modelError: 'Model error', offlineGoogle: 'Google speech needs internet. Switch to On-device.' },
  am: { tagline: 'በቋንቋዎ ይናገሩ። በማንኛውም ቋንቋ ይጻፉ።', input: 'ግብዓት', output: 'ውጤት', mode: 'ሁነታ', modeExact: 'ልክ', modeClean: 'ንጹህ', modeTranslate: 'ተርጉም',
    tap: 'ማይክሮፎኑን ነክተው ይናገሩ።', cancel: 'ሰርዝ', transcript: 'ግልባጭ', translation: 'ትርጉም', copy: 'ቅዳ', copyTr: 'ትርጉሙን ቅዳ', share: 'አጋራ',
    translate: 'ተርጉም', listen: 'አዳምጥ', save: '.txt አስቀምጥ', discard: 'አስወግድ', models: 'ሞዴሎች እና ግላዊነት', warm: 'ለአሁኑ ቋንቋዎች ሞዴሎችን አውርድ', install: 'መተግበሪያውን ጫን',
    privacy: 'ሁሉም ነገር በዚህ የአሳሽ ትር ውስጥ ይሰራል። ድምጽ ከመሣሪያዎ አይወጣም። ሞዴሎች (30–115 MB) አንድ ጊዜ ወርደው ከመስመር ውጭ ለመጠቀም ይቀመጣሉ።',
    kbnote: 'አሳሾች የስርዓት ኪቦርድ መስጠት አይችሉም። በሌሎች መተግበሪያዎች ውስጥ ለመጻፍ የአንድሮይድ መተግበሪያውን ይጠቀሙ፤ እዚህ ጽሑፉን ይቅዱ ወይም ያጋሩ።',
    listening: 'እያዳመጠ ነው… ለማቆም ይንኩ', loading: 'ሞዴል እየተጫነ ነው', transcribing: 'በዚህ መሣሪያ ላይ እየተጻፈ ነው…', translating: 'በዚህ መሣሪያ ላይ እየተተረጎመ ነው…', empty: 'ምንም አልተሰማም። እንደገና ይሞክሩ።',
    micDenied: 'የማይክሮፎን ፈቃድ ያስፈልጋል።', noTr: 'ለዚህ ጥንድ የትርጉም ሞዴል የለም።', copied: 'ተቀድቷል', local: 'በዚህ መሣሪያ ላይ ተሰርቷል', amNote: 'የአማርኛ ሞዴል (የሙከራ)',
    kb: 'የአማርኛ ኪቦርድ', editHint: 'ለማረም በሳጥኑ ውስጥ ያለውን ቃል ይንኩ።', space: 'ክፍተት',
    workerCrash: 'የንግግር ሞዴሉ ቆሟል (ማህደረ ትውስታ አልበቃም)። ሌሎች ትሮችን ዘግተው እንደገና ይሞክሩ።', review: 'ይህን ጽሑፍ ያረጋግጡ፤ ሞዴሉ ደግሟል',
    engineLocal: 'በስልኩ ላይ · ግላዊ', engineGoogle: 'Google · በመስመር ላይ፣ የበለጠ ትክክል', googleNote: 'የChrome አብሮ የተሰራውን የGoogle ንግግር አገልግሎት ይጠቀማል፤ ድምጽዎ ወደ Google ይላካል። ነጻ ነው፣ ኢንተርኔት ያስፈልጋል።',
    googleUnsupported: 'ይህ አሳሽ አብሮ የተሰራ የንግግር ማወቂያ የለውም። Chromeን ይጠቀሙ።', liveListening: 'እያዳመጠ ነው… ቃላት ሲናገሩ ይታያሉ', preparing: 'ሞዴል እየተዘጋጀ', ready: 'ሞዴሉ ዝግጁ ነው', modelError: 'የሞዴል ስህተት', offlineGoogle: 'Google ንግግር ኢንተርኔት ያስፈልገዋል። ወደ በስልኩ ላይ ይቀይሩ።' },
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

// ---- inference worker ----------------------------------------------------------------------
let worker = null, seq = 0;
const pending = new Map();
function startWorker() {
  worker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' });
  worker.onmessage = ({ data: m }) => {
    const p = pending.get(m.id); if (!p) return;
    // Model download/loading is shown only as a small header chip; the page stays usable.
    if (m.type === 'progress') setChip(`${L.preparing} ${Math.round(m.progress)}%`, 'busy');
    else if (m.type === 'status') {
      if (m.stage === 'loading') setChip(`${L.preparing}…`, 'busy');
      else if (!p.background) ui.status.textContent = m.stage === 'translating' ? L.translating : L.transcribing;
    } else if (m.type === 'result') { pending.delete(m.id); setChip(L.ready, 'ok'); p.resolve(m); }
    else if (m.type === 'error') { pending.delete(m.id); setChip(L.modelError, 'err'); p.reject(new Error(m.message)); }
  };
  // A crashed worker (e.g. out of memory) must not take the page down: fail pending jobs, restart.
  worker.onerror = (e) => {
    e.preventDefault?.();
    for (const [, p] of pending) p.reject(new Error(L.workerCrash));
    pending.clear(); ui.progress.hidden = true;
    try { worker.terminate(); } catch {}
    startWorker();
  };
}
startWorker();
function run(msg, transfer = [], background = false) {
  return new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject, background }); worker.postMessage({ ...msg, id }, transfer); });
}
function setChip(text, state) { const c = $('modelChip'); if (!c) return; c.hidden = false; c.textContent = text; c.dataset.state = state; }
// Warm the speech model in the background as soon as the page is idle, and whenever the input
// language changes, so the first tap on the mic does not wait for a download.
function warmSpeech() {
  if (engine() !== 'local') return;
  run({ type: 'warm', task: 'automatic-speech-recognition', model: SPEECH[ui.inLang.value] }, [], true).catch(() => {});
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
  levelTimer = setInterval(() => { analyser.getByteTimeDomainData(buf); let s = 0; for (const v of buf) { const d = (v - 128) / 128; s += d * d; } const lv = Math.min(1, Math.sqrt(s / buf.length) * 4); ui.level.style.width = (lv * 100) + '%'; document.documentElement.style.setProperty('--lvl', lv.toFixed(3)); }, 80);
  ui.mic.classList.add('rec'); document.body.classList.add('recording'); ui.mic.setAttribute('aria-label', 'Stop'); ui.status.textContent = L.listening; ui.cancel.hidden = false;
  // One utterance is capped at 30 s: a single Whisper window keeps browser memory low on phones.
  const rec = mediaRecorder;
  setTimeout(() => { if (rec.state === 'recording') rec.stop(); }, MAX_UTTERANCE_MS);
}
const MAX_UTTERANCE_MS = 30000;

function stopRecording() { if (mediaRecorder?.state === 'recording') mediaRecorder.stop(); }

function teardown() {
  clearInterval(levelTimer); ui.level.style.width = '0'; document.documentElement.style.setProperty('--lvl', '0');
  stream?.getTracks().forEach((t) => t.stop()); stream = null;
  audioCtx?.close(); audioCtx = null;
  ui.mic.classList.remove('rec'); document.body.classList.remove('recording'); ui.mic.setAttribute('aria-label', 'Start dictation'); ui.cancel.hidden = true;
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
  } catch (err) { console.error(err); ui.status.textContent = describeError(err); }
}

function describeError(err) {
  // transformers.js may throw an AggregateError or a plain Error; show everything we know.
  const parts = [];
  if (err?.errors?.length) parts.push(...err.errors.map((e) => e.message || String(e)));
  parts.push(err?.message || String(err));
  return 'Error: ' + [...new Set(parts)].join(' | ');
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
  document.body.classList.add('busy');
  try { await transcribeInner(pcm); } finally { document.body.classList.remove('busy'); }
}
async function transcribeInner(pcm) {
  const lang = ui.inLang.value;
  // Single 30 s window: one Whisper pass keeps browser memory low on phones.
  const samples = pcm.length > 16000 * 30 ? pcm.slice(0, 16000 * 30) : pcm;
  lastAudioMs = Math.round(samples.length / 16);
  const r = await run({ type: 'asr', model: SPEECH[lang], samples, language: WHISPER_LANG[lang] }, [samples.buffer]);
  const g = guard(r.text.replace(/\s+/g, ' '), lastAudioMs);
  let text = g.text;
  if (ui.mode.value !== 'exact') text = clean(text, lang);
  if (!text) { ui.status.textContent = L.empty; return; }
  showResult(text, `${r.ms} ms · RTF ${(r.ms / lastAudioMs).toFixed(2)} · ${SPEECH[lang]} · ${L.local}`, g.needsReview);
  if (ui.mode.value === 'translate') await doTranslate();
}

function showResult(text, stats, needsReview) {
  ui.text.value = text;
  ui.stats.textContent = stats + (needsReview ? ` · ${L.review}` : '');
  ui.stats.classList.toggle('warn', !!needsReview);
  ui.result.hidden = false;
  if (matchMedia('(max-width: 899px)').matches) requestAnimationFrame(() => ui.result.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  ui.translation.hidden = true; ui.trLabel.hidden = true; ui.copyTr.hidden = true; ui.trError.hidden = true;
  ui.status.textContent = L.tap;
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
  const r = await run({ type: 'translate', model: m.id, text, prefix: m.prefix });
  const t0 = performance.now() - r.ms;
  const parts = [r.text];
  ui.translation.textContent = parts.join(' ');
  ui.translation.hidden = false; ui.trLabel.hidden = false; ui.copyTr.hidden = false;
  ui.status.textContent = `${Math.round(performance.now() - t0)} ms · ${m.id} · ${L.local}`;
}

// ---- actions -----------------------------------------------------------------------------
// ---- engine: on-device (default) or Chrome's built-in Google speech service (opt-in) ----------
const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
const BCP47 = { am: 'am-ET', en: 'en-US', ar: 'ar-SA', so: 'so-SO' };
function engine() { return localStorage.getItem('engine') === 'google' && SR ? 'google' : 'local'; }
function syncEngine() {
  document.querySelectorAll('[data-engine]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.engine === engine())));
  const n = $('engineNote'); if (n) n.textContent = engine() === 'google' ? L.googleNote : '';
  const net = $('net'); if (net && engine() === 'google') { net.textContent = 'Google'; net.dataset.state = 'cloud'; } else syncOnline();
}
document.querySelectorAll('[data-engine]').forEach((b) => b.addEventListener('click', () => {
  if (b.dataset.engine === 'google' && !SR) { ui.status.textContent = L.googleUnsupported; return; }
  localStorage.setItem('engine', b.dataset.engine); syncEngine(); warmSpeech();
}));

let rec = null, finalText = '', recCancelled = false;
function startGoogle() {
  rec = new SR();
  rec.lang = BCP47[ui.inLang.value] || 'am-ET';
  rec.continuous = true; rec.interimResults = true; rec.maxAlternatives = 1;
  finalText = ''; recCancelled = false;
  ui.text.value = ''; ui.result.hidden = false; ui.translation.hidden = true; ui.trLabel.hidden = true; ui.copyTr.hidden = true; ui.trError.hidden = true;
  ui.stats.textContent = '';
  rec.onresult = (e) => {
    let interim = '';
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const t = e.results[i][0].transcript;
      if (e.results[i].isFinal) finalText += t + ' '; else interim += t;
    }
    ui.text.value = (finalText + interim).replace(/\s+/g, ' ').trim();
  };
  rec.onerror = (e) => {
    const map = { 'not-allowed': L.micDenied, 'service-not-allowed': L.micDenied, 'language-not-supported': L.googleUnsupported, 'no-speech': L.empty, network: L.offlineGoogle };
    ui.status.textContent = map[e.error] || `Error: ${e.error}`;
  };
  rec.onend = async () => {
    ui.mic.classList.remove('rec'); document.body.classList.remove('recording'); ui.cancel.hidden = true; rec = null;
    if (recCancelled) { ui.text.value = ''; ui.result.hidden = true; ui.status.textContent = L.tap; return; }
    let text = ui.text.value.trim();
    if (!text) { if (!/Error|ፈቃድ|permission/i.test(ui.status.textContent)) ui.status.textContent = L.empty; ui.result.hidden = true; return; }
    if (ui.mode.value !== 'exact') text = clean(text, ui.inLang.value);
    showResult(text, `Google speech (${rec?.lang || BCP47[ui.inLang.value]}) · online`, false);
    if (ui.mode.value === 'translate') await doTranslate().catch((err) => { ui.trError.textContent = describeError(err); ui.trError.hidden = false; });
  };
  rec.start();
  ui.mic.classList.add('rec'); document.body.classList.add('recording'); ui.cancel.hidden = false; ui.status.textContent = L.liveListening;
}

ui.mic.onclick = () => {
  if (engine() === 'google') { if (rec) rec.stop(); else startGoogle(); return; }
  if (mediaRecorder?.state === 'recording') stopRecording(); else startRecording();
};
ui.cancel.onclick = () => { if (rec) { recCancelled = true; rec.abort(); return; } cancelled = true; stopRecording(); };
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
    await run({ type: 'warm', task: 'automatic-speech-recognition', model: SPEECH[ui.inLang.value] });
    ui.modelStatus.textContent = 'Models cached for offline use.';
  } catch (e) { ui.modelStatus.textContent = String(e.message || e); }
};
ui.inLang.onchange = () => { localStorage.setItem('in', ui.inLang.value); setTimeout(warmSpeech, 300); ui.modelStatus.textContent = ui.inLang.value === 'am' ? L.amNote : ''; };
ui.outLang.onchange = () => localStorage.setItem('out', ui.outLang.value);
ui.mode.onchange = () => localStorage.setItem('mode', ui.mode.value);
ui.uiLang.onchange = () => applyLang(ui.uiLang.value);

// Swap input/output languages (only when the output language is also a speech language).
$('swap')?.addEventListener('click', () => {
  const a = ui.inLang.value, b = ui.outLang.value;
  if (![...ui.inLang.options].some((o) => o.value === b)) return;
  ui.inLang.value = b; ui.outLang.value = a; ui.inLang.onchange(); ui.outLang.onchange();
});
// Segmented mode control mirrors the hidden <select id="mode">.
document.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => {
  ui.mode.value = b.dataset.mode; ui.mode.onchange(); syncMode();
}));
function syncMode() { document.querySelectorAll('[data-mode]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.mode === ui.mode.value))); }
// Offline badge
function syncOnline() { const el = $('net'); if (el) { el.textContent = navigator.onLine ? 'On-device' : 'Offline'; el.dataset.state = navigator.onLine ? 'on' : 'off'; } }
addEventListener('online', syncOnline); addEventListener('offline', syncOnline);

// ---- init ---------------------------------------------------------------------------------
ui.inLang.value = localStorage.getItem('in') || 'am';
ui.outLang.value = localStorage.getItem('out') || 'en';
ui.mode.value = localStorage.getItem('mode') || 'clean';
ui.uiLang.value = localStorage.getItem('ui') || (navigator.language.startsWith('am') ? 'am' : 'en');
applyLang(ui.uiLang.value);
ui.inLang.onchange();
syncMode(); syncOnline(); syncEngine();
// Background warm-up once the page is idle (does not block first paint or the mic).
(window.requestIdleCallback || ((f) => setTimeout(f, 1500)))(() => warmSpeech());
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js');
// On-screen phonetic Ge'ez keyboard for phones without an Amharic keyboard.
const geezKeyboard = attachGeezKeyboard(ui.text, $('kb'), { space: L.space });
$('kbToggle').onclick = () => { const kb = $('kb'); kb.hidden = !kb.hidden; if (!kb.hidden) ui.text.focus({ preventScroll: true }); };
let installEvent = null;
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installEvent = e; ui.install.hidden = false; });
ui.install.onclick = () => installEvent?.prompt();
