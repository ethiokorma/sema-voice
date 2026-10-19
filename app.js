// Sema Voice PWA — app shell, engines, history. Models run in worker.js (never on the page thread).
import { attachGeezKeyboard } from './keyboard.js';
import { guard } from './guard.js';

const $ = (id) => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem('sema.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('sema.' + k, JSON.stringify(v)); } catch {} },
  del(k) { try { localStorage.removeItem('sema.' + k); } catch {} },
};

// ---------------------------------------------------------------- strings (en / am)
const S = {
  en: {
    playRec: 'Play recording', retry: 'Try again', newNote: 'New note', recSaved: 'Your recording is saved. Tap “Try again” to transcribe it again.', stopPlay: 'Stop',
    restored: 'Your last note was restored.',
    eServer: 'Sema server (most accurate)', eServerDesc: 'Our best Amharic model (about 22% word error) running on your own computer. Works in any browser, including iPhone. Audio goes only to your server.', eServerNo: 'Not connected. Open the link from your Sema server, or add it under Sema server below.', serverBadge: 'Best',
    serverSec: 'Sema server', serverHelp: 'Use the most accurate model running on your own computer. Open the link shown by the server, or paste its address and key here.', serverKeyPh: 'Access key', serverTest: 'Save & test', serverForget: 'Forget',
    srvOk: 'Connected: {model}', srvBadKey: 'The server is reachable but the access key is wrong.', srvDown: 'Cannot reach the server. Is it running, and is the address right?', srvBusy: 'Sending audio to your Sema server…', srvLinked: 'Sema server connected. It is now your recognition engine.',
    speakTitle: 'Speak', historyTitle: 'History', translateTitle: 'Translate', settingsTitle: 'Settings',
    tabSpeak: 'Speak', tabHistory: 'History', tabTranslate: 'Translate', tabSettings: 'Settings',
    modeExact: 'Exact', modeClean: 'Clean', modeTranslate: '+ English',
    heroTitle: 'Speak Amharic. Get text.', heroSub: 'Tap the microphone and talk naturally. Your words appear here.',
    translation: 'Translation', copy: 'Copy', translate: 'Translate', share: 'Share', edit: 'Edit', listen: 'Listen', clear: 'Clear',
    searchPh: 'Search your notes', historyEmpty: 'Your dictations will appear here. They stay on this device.',
    tPh: 'Type or paste text', amKeyboard: 'Amharic keyboard', tNote: 'Translation runs on this device (OPUS-MT). Amharic → English is usable; English → Amharic is basic.',
    engineSec: 'Recognition engine', langSec: 'Languages', spokenLang: 'I speak', targetLang: 'Translate to', uiLangLabel: 'App language',
    offlineSec: 'Offline model', prepare: 'Download & prepare now', appSec: 'App', install: 'Install app', clearHistory: 'Delete all history', diagnostics: 'Diagnostics',
    privacy: 'On-device engines keep your audio in this browser. The Google engine sends audio to Google. History is saved only on this device.',
    welcomeTitle: 'Welcome to Sema Voice', welcomeSub: 'Choose how your voice is turned into text. You can change this any time in Settings.', start: 'Start', done: 'Done', dismiss: 'Dismiss',
    eApple: 'Apple speech (online)', eAppleDesc: 'iPhone has no Google speech: browsers there use Apple speech. Works for English and Arabic. Needs internet; audio goes to Apple.', eAppleNoAm: 'Apple speech does not support Amharic. On iPhone use Sema server (most accurate) or On-device.', eGoogle: 'Google (online)', eGoogleDesc: 'Most accurate for Amharic in the browser. Words appear live as you speak. Audio is sent to Google. Needs internet and Chrome.',
    eLocal: 'On-device (private)', eLocalDesc: 'Works offline, audio never leaves this phone. Uses a small 56 MB model: fast, but makes more mistakes.',
    recommended: 'Recommended', private: 'Private', unavailable: 'Not available in this browser',
    tapToSpeak: 'Tap to speak', listening: 'Listening…', liveListening: 'Listening… words appear as you speak', transcribing: 'Writing it down…', translating: 'Translating…',
    preparing: 'Preparing model', ready: 'Model ready', modelError: 'Model error',
    empty: "I didn't catch anything. Try again a bit closer.", micDenied: 'Microphone permission is needed. Allow it in the browser settings.', googleUnavail: 'Google speech is not available in this browser. Use Chrome with the Google app installed and up to date, or pick another engine.', micBusy: 'Another app is using the microphone. Close it and try again.', switchEngine: 'Choose engine',
    googleOffline: 'Google speech needs internet. Switch to On-device in Settings.', googleLang: 'Google speech does not support this language here.',
    copied: 'Copied', saved: 'Saved to History', deleted: 'Deleted', review: 'check this text: the model repeated itself',
    noTr: 'No translation model for this language pair.', workerCrash: 'The on-device model stopped (probably not enough memory). Try the Google engine, or close other tabs.',
    crashed: 'Sema Voice closed unexpectedly last time while {stage}. The on-device model may be too heavy for this phone.', useGoogle: 'Use Google engine',
    stage_loading: 'loading the on-device model', stage_transcribing: 'transcribing on this device', stage_translating: 'translating',
    offlineReady: 'The on-device model is ready and works without internet.', offlineNot: 'Not downloaded yet. It downloads automatically the first time you use the on-device engine (about 56 MB, Wi-Fi recommended).',
    lowMem: 'This device reports {mem} GB memory. The on-device model may crash here; the Google engine is safer.',
    confirmClear: 'Tap again to delete all history', noVoice: 'No voice for this language in this browser.',
    eBest: 'Best accuracy (on this computer)', eBestDesc: 'Our most accurate Amharic model (about 22% word error, vs 60–80% for the small model). Private and offline after a one-time ~430 MB download. Needs a computer or a phone with 8 GB memory.', eBestNo: 'Needs a computer or a phone with 8 GB memory.', bestBadge: 'Most accurate',
  },
  am: {
    playRec: 'ቅጂውን አጫውት', retry: 'እንደገና ሞክር', newNote: 'አዲስ ማስታወሻ', recSaved: 'ቅጂዎ ተቀምጧል። እንደገና ለመጻፍ “እንደገና ሞክር” ይንኩ።', stopPlay: 'አቁም',
    restored: 'የመጨረሻው ማስታወሻዎ ተመልሷል።',
    eServer: 'ሰማ ሰርቨር (በጣም ትክክለኛ)', eServerDesc: 'ምርጡ የአማርኛ ሞዴላችን (~22% የቃል ስህተት) በራስዎ ኮምፒውተር ላይ ይሰራል። በማንኛውም አሳሽ፣ iPhone ጨምሮ ይሰራል። ድምጽ ወደ ሰርቨርዎ ብቻ ይሄዳል።', eServerNo: 'አልተገናኘም። ከሰማ ሰርቨርዎ የሚመጣውን ሊንክ ይክፈቱ ወይም ከታች ያክሉት።', serverBadge: 'ምርጥ',
    serverSec: 'ሰማ ሰርቨር', serverHelp: 'በራስዎ ኮምፒውተር ላይ የሚሰራውን በጣም ትክክለኛ ሞዴል ይጠቀሙ። ሰርቨሩ የሚያሳየውን ሊንክ ይክፈቱ ወይም አድራሻውንና ቁልፉን እዚህ ይለጥፉ።', serverKeyPh: 'የመግቢያ ቁልፍ', serverTest: 'አስቀምጥና ሞክር', serverForget: 'እርሳ',
    srvOk: 'ተገናኝቷል፦ {model}', srvBadKey: 'ሰርቨሩ ይገኛል ግን ቁልፉ ትክክል አይደለም።', srvDown: 'ሰርቨሩን ማግኘት አልተቻለም። እየሰራ ነው? አድራሻው ትክክል ነው?', srvBusy: 'ድምጽ ወደ ሰማ ሰርቨርዎ እየተላከ ነው…', srvLinked: 'ሰማ ሰርቨር ተገናኝቷል። አሁን የማወቂያ ሞተርዎ ነው።',
    speakTitle: 'ይናገሩ', historyTitle: 'ታሪክ', translateTitle: 'ተርጉም', settingsTitle: 'ቅንብሮች',
    tabSpeak: 'ይናገሩ', tabHistory: 'ታሪክ', tabTranslate: 'ተርጉም', tabSettings: 'ቅንብሮች',
    modeExact: 'ልክ', modeClean: 'ንጹህ', modeTranslate: '+ እንግሊዝኛ',
    heroTitle: 'አማርኛ ይናገሩ። ጽሑፍ ያግኙ።', heroSub: 'ማይክሮፎኑን ነክተው በተፈጥሮ ይናገሩ። ቃላትዎ እዚህ ይታያሉ።',
    translation: 'ትርጉም', copy: 'ቅዳ', translate: 'ተርጉም', share: 'አጋራ', edit: 'አርትዕ', listen: 'አዳምጥ', clear: 'አጽዳ',
    searchPh: 'ማስታወሻዎችዎን ይፈልጉ', historyEmpty: 'የተናገሯቸው ጽሑፎች እዚህ ይታያሉ። በዚህ መሣሪያ ላይ ብቻ ይቀመጣሉ።',
    tPh: 'ጽሑፍ ይተይቡ ወይም ይለጥፉ', amKeyboard: 'የአማርኛ ኪቦርድ', tNote: 'ትርጉም በዚህ መሣሪያ ላይ ይሰራል። አማርኛ → እንግሊዝኛ ጥሩ ነው፤ እንግሊዝኛ → አማርኛ መሠረታዊ ነው።',
    engineSec: 'የማወቂያ ሞተር', langSec: 'ቋንቋዎች', spokenLang: 'የምናገረው', targetLang: 'የሚተረጎመው', uiLangLabel: 'የመተግበሪያ ቋንቋ',
    offlineSec: 'ከመስመር ውጭ ሞዴል', prepare: 'አሁን አውርድና አዘጋጅ', appSec: 'መተግበሪያ', install: 'መተግበሪያውን ጫን', clearHistory: 'ሁሉንም ታሪክ ሰርዝ', diagnostics: 'ምርመራ',
    privacy: 'በስልኩ ላይ የሚሰሩ ሞተሮች ድምጽዎን በዚህ አሳሽ ውስጥ ያቆያሉ። የGoogle ሞተር ድምጽን ወደ Google ይልካል። ታሪክ በዚህ መሣሪያ ላይ ብቻ ይቀመጣል።',
    welcomeTitle: 'ወደ ሰማ ቮይስ እንኳን በደህና መጡ', welcomeSub: 'ድምጽዎ ወደ ጽሑፍ የሚቀየርበትን መንገድ ይምረጡ። በኋላ በቅንብሮች መቀየር ይችላሉ።', start: 'ጀምር', done: 'ተጠናቀቀ', dismiss: 'ዝጋ',
    eApple: 'Apple ንግግር (በመስመር ላይ)', eAppleDesc: 'iPhone ላይ የGoogle ንግግር የለም፤ አሳሾቹ የApple ንግግር ይጠቀማሉ። ለእንግሊዝኛና ለዓረብኛ ይሰራል። ኢንተርኔት ያስፈልጋል፤ ድምጽ ወደ Apple ይላካል።', eAppleNoAm: 'የApple ንግግር አማርኛን አይደግፍም። በiPhone ላይ ሰማ ሰርቨርን (በጣም ትክክለኛ) ወይም በስልኩ ላይን ይጠቀሙ።', eGoogle: 'Google (በመስመር ላይ)', eGoogleDesc: 'በአሳሽ ውስጥ ለአማርኛ በጣም ትክክለኛ። ቃላት ሲናገሩ ወዲያው ይታያሉ። ድምጽ ወደ Google ይላካል። ኢንተርኔትና Chrome ያስፈልጋል።',
    eLocal: 'በስልኩ ላይ (ግላዊ)', eLocalDesc: 'ያለ ኢንተርኔት ይሰራል፤ ድምጽ ከስልኩ አይወጣም። ትንሽ 56 MB ሞዴል ነው፤ ፈጣን ነው ግን ብዙ ስህተት ይሰራል።',
    recommended: 'የሚመከር', private: 'ግላዊ', unavailable: 'በዚህ አሳሽ አይገኝም',
    tapToSpeak: 'ለመናገር ይንኩ', listening: 'እያዳመጠ ነው…', liveListening: 'እያዳመጠ ነው… ቃላት ሲናገሩ ይታያሉ', transcribing: 'እየጻፈ ነው…', translating: 'እየተረጎመ ነው…',
    preparing: 'ሞዴል እየተዘጋጀ', ready: 'ሞዴሉ ዝግጁ ነው', modelError: 'የሞዴል ስህተት',
    empty: 'ምንም አልሰማሁም። ቀረብ ብለው እንደገና ይሞክሩ።', micDenied: 'የማይክሮፎን ፈቃድ ያስፈልጋል። በአሳሹ ቅንብሮች ውስጥ ይፍቀዱ።', googleUnavail: 'የGoogle ንግግር በዚህ አሳሽ አይገኝም። Chrome ከተዘመነ የGoogle መተግበሪያ ጋር ይጠቀሙ ወይም ሌላ ሞተር ይምረጡ።', micBusy: 'ሌላ መተግበሪያ ማይክሮፎኑን እየተጠቀመ ነው። ዘግተው እንደገና ይሞክሩ።', switchEngine: 'ሞተር ይምረጡ',
    googleOffline: 'የGoogle ንግግር ኢንተርኔት ያስፈልገዋል። በቅንብሮች ወደ በስልኩ ላይ ይቀይሩ።', googleLang: 'የGoogle ንግግር ይህን ቋንቋ እዚህ አይደግፍም።',
    copied: 'ተቀድቷል', saved: 'በታሪክ ተቀምጧል', deleted: 'ተሰርዟል', review: 'ይህን ጽሑፍ ያረጋግጡ፤ ሞዴሉ ደግሟል',
    noTr: 'ለዚህ ቋንቋ ጥንድ የትርጉም ሞዴል የለም።', workerCrash: 'የስልኩ ሞዴል ቆሟል (ማህደረ ትውስታ አልበቃም)። የGoogle ሞተርን ይሞክሩ ወይም ሌሎች ትሮችን ይዝጉ።',
    crashed: 'ባለፈው ጊዜ ሰማ ቮይስ {stage} ላይ ሳለ በድንገት ተዘግቷል። የስልኩ ሞዴል ለዚህ ስልክ ከባድ ሊሆን ይችላል።', useGoogle: 'የGoogle ሞተር ተጠቀም',
    stage_loading: 'የስልኩን ሞዴል በመጫን', stage_transcribing: 'በስልኩ ላይ በመጻፍ', stage_translating: 'በመተርጎም',
    offlineReady: 'የስልኩ ሞዴል ዝግጁ ነው፤ ያለ ኢንተርኔት ይሰራል።', offlineNot: 'ገና አልወረደም። የስልኩን ሞተር መጀመሪያ ሲጠቀሙ በራሱ ይወርዳል (56 MB ገደማ፤ Wi-Fi ይመከራል)።',
    lowMem: 'ይህ መሣሪያ {mem} GB ማህደረ ትውስታ አለው። የስልኩ ሞዴል ሊቆም ይችላል፤ የGoogle ሞተር ይሻላል።',
    confirmClear: 'ሁሉንም ታሪክ ለመሰረዝ እንደገና ይንኩ', noVoice: 'በዚህ አሳሽ ለዚህ ቋንቋ ድምጽ የለም።',
    eBest: 'ምርጥ ትክክለኛነት (በዚህ ኮምፒውተር)', eBestDesc: 'በጣም ትክክለኛው የአማርኛ ሞዴላችን (22% ገደማ ስህተት)። አንድ ጊዜ ~430 MB ከወረደ በኋላ ግላዊና ከመስመር ውጭ ይሰራል። ኮምፒውተር ወይም 8 GB ማህደረ ትውስታ ያለው ስልክ ያስፈልጋል።', eBestNo: 'ኮምፒውተር ወይም 8 GB ማህደረ ትውስታ ያለው ስልክ ያስፈልጋል።', bestBadge: 'በጣም ትክክለኛ',
  },
};
let L = S.en;
const t = (k, vars = {}) => (L[k] || S.en[k] || k).replace(/\{(\w+)\}/g, (_, v) => vars[v] ?? '');

// ---------------------------------------------------------------- languages & models
const LANGS = {
  am: { name: 'አማርኛ', en: 'Amharic', bcp: 'am-ET', whisper: 'amharic', model: 'whisper-tiny-amharic' },
  en: { name: 'English', en: 'English', bcp: 'en-US', whisper: 'english', model: 'Xenova/whisper-tiny' },
  ar: { name: 'العربية', en: 'Arabic', bcp: 'ar-SA', whisper: 'arabic', model: 'Xenova/whisper-tiny' },
};
const OUT = { en: 'English', am: 'አማርኛ', ar: 'العربية', so: 'Soomaali', ti: 'ትግርኛ' };
const MARIAN = { am: '>>amh<<', ar: '>>ara<<', so: '>>som<<', ti: '>>tir<<' };
function trModel(from, to) {
  if (from === to) return null;
  if (to === 'en') return { id: 'Xenova/opus-mt-mul-en', prefix: '' };
  if (from === 'en' && MARIAN[to]) return { id: 'Xenova/opus-mt-en-mul', prefix: MARIAN[to] + ' ' };
  return null;
}

const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
const isPhone = /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent);
// Every iPhone/iPad browser (Safari, Chrome, Edge) is WebKit: its SpeechRecognition is Apple's, not Google's,
// and Apple speech has no Amharic. iPadOS reports a Mac user agent, hence the touch check.
const isIOS = /iPhone|iPad|iPod/i.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1);
const mem = navigator.deviceMemory || 0; // Chrome-only hint, GB, capped at 8
const state = {
  engine: store.get('engine', null), in: store.get('in', 'am'), out: store.get('out', 'en'), mode: store.get('mode', 'clean'),
  ui: store.get('ui', navigator.language.startsWith('am') ? 'am' : 'en'),
};
if (!LANGS[state.in]) state.in = 'am';
// Sema server (self-hosted best model). A link like  .../#server=<url>&key=<key>  configures it in one tap.
// The key travels in the #fragment, which browsers never send to GitHub Pages.
let server = store.get('server', null);
let serverLinked = false;
(() => {
  const h = new URLSearchParams(location.hash.slice(1));
  const u = h.get('server'), k = h.get('key');
  if (u && k) {
    server = { url: u.replace(/\/+$/, ''), key: k }; store.set('server', server);
    state.engine = 'server'; store.set('engine', 'server'); serverLinked = true;
    history.replaceState(null, '', location.pathname + location.search);
  }
})();

// ---------------------------------------------------------------- crash detection
// A "stage" marker is written before heavy work and cleared after. If it is still present on
// the next start, the tab died during that stage (typically out of memory).
const STAGE_KEY = 'stage';
function stageBegin(s) { store.set(STAGE_KEY, { s, at: Date.now() }); }
function stageEnd() { store.del(STAGE_KEY); }
const crashed = store.get(STAGE_KEY, null);
stageEnd();
if (crashed) store.set('crashes', [...store.get('crashes', []).slice(-4), { ...crashed, ua: navigator.userAgent.slice(0, 120), mem }]);

// ---------------------------------------------------------------- inference worker
let worker = null, seq = 0, workerFailed = false;
const pending = new Map();
function ensureWorker() {
  if (worker) return worker;
  worker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' });
  worker.onmessage = ({ data: m }) => {
    const p = pending.get(m.id); if (!p) return;
    if (m.type === 'progress') setChip(`${t('preparing')} ${Math.round(m.progress)}%`, 'busy');
    else if (m.type === 'status') {
      if (m.stage === 'loading') { setChip(`${t('preparing')}…`, 'busy'); stageBegin('loading'); }
      else { stageBegin(m.stage); if (!p.background) setStatus(m.stage === 'translating' ? t('translating') : t('transcribing')); }
    } else if (m.type === 'result') { pending.delete(m.id); stageEnd(); setChip(t('ready'), 'ok'); hideChipSoon(); p.resolve(m); }
    else if (m.type === 'error') { pending.delete(m.id); stageEnd(); setChip(t('modelError'), 'err'); p.reject(new Error(m.message)); }
  };
  worker.onerror = (e) => {
    e.preventDefault?.();
    workerFailed = true; stageEnd();
    for (const [, p] of pending) p.reject(new Error(t('workerCrash')));
    pending.clear(); setChip(t('modelError'), 'err');
    try { worker.terminate(); } catch {}
    worker = null;
  };
  return worker;
}
function run(msg, transfer = [], background = false) {
  ensureWorker();
  return new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject, background }); worker.postMessage({ ...msg, id }, transfer); });
}
let chipTimer = 0;
function setChip(text, st) { const c = $('modelChip'); clearTimeout(chipTimer); c.hidden = false; c.textContent = text; c.dataset.state = st; }
function hideChipSoon() { clearTimeout(chipTimer); chipTimer = setTimeout(() => { $('modelChip').hidden = true; }, 2500); }

// ---------------------------------------------------------------- UI helpers
function setStatus(s) { $('status').textContent = s; }
let toastTimer = 0;
function toast(s) { const el = $('toast'); el.textContent = s; el.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => (el.hidden = true), 1800); }
function describe(err) {
  const parts = []; if (err?.errors?.length) parts.push(...err.errors.map((e) => e.message || String(e)));
  parts.push(err?.message || String(err)); return [...new Set(parts)].join(' | ');
}
function applyLang() {
  L = S[state.ui] || S.en; document.documentElement.lang = state.ui;
  document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
  document.querySelectorAll('[data-i18n-ph]').forEach((el) => { el.placeholder = t(el.dataset.i18nPh); });
  const active = document.querySelector('.screen:not([hidden])'); if (active) $('screenTitle').textContent = t(active.dataset.title);
  renderEngineOptions(); renderLangPair(); renderOffline(); if (!recActive()) setStatus(t('tapToSpeak'));
}

// ---------------------------------------------------------------- navigation
function showTab(name) {
  document.querySelectorAll('.screen').forEach((s) => (s.hidden = s.id !== 'screen-' + name));
  document.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('on', b.dataset.tab === name));
  $('screenTitle').textContent = t($('screen-' + name).dataset.title);
  if (name === 'history') renderHistory();
  if (name === 'settings') { renderDiag(); renderServer(); }
  scrollTo({ top: 0 });
}
document.querySelectorAll('[data-tab]').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));

// ---------------------------------------------------------------- engines
const BEST_MANIFEST = 'models/ethio-ctc/manifest.json';
function engineAvailable(e) { return e === 'google' ? !!SR && !(isIOS && state.in === 'am') : e === 'best' ? (!isPhone || mem >= 8) : e === 'server' ? !!server : true; }
function engine() {
  if (state.engine && engineAvailable(state.engine)) return state.engine;
  return engineAvailable('google') ? 'google' : server ? 'server' : 'local';
}
function setEngine(e) {
  state.engine = e; store.set('engine', e); renderEngineOptions();
  if (e === 'local' && !isPhone) warmLocal(); // phones load the model only when the mic is used
  if (e === 'best') warmBest();
}
function engineCard(e, host, onPick) {
  const avail = engineAvailable(e);
  const b = document.createElement('button');
  b.type = 'button'; b.className = 'opt'; b.setAttribute('role', 'radio');
  b.setAttribute('aria-checked', String(engine() === e && avail)); if (!avail) b.setAttribute('aria-disabled', 'true');
  const title = { google: t(isIOS ? 'eApple' : 'eGoogle'), local: t('eLocal'), best: t('eBest'), server: t('eServer') }[e];
  const desc = !avail ? (e === 'best' ? t('eBestNo') : e === 'server' ? t('eServerNo') : e === 'google' && isIOS && SR ? t('eAppleNoAm') : t('unavailable')) : { google: t(isIOS ? 'eAppleDesc' : 'eGoogleDesc'), local: t('eLocalDesc'), best: t('eBestDesc'), server: t('eServerDesc') }[e];
  const badge = e === 'google' ? (isIOS ? '' : `<span class="badge">${t('recommended')}</span>`) : e === 'best' ? `<span class="badge">${t('bestBadge')}</span>` : e === 'server' ? `<span class="badge">${t('serverBadge')}</span>` : `<span class="badge">${t('private')}</span>`;
  b.innerHTML = `<span class="radio"></span><span><span class="opt-title">${title} ${badge}</span><span class="opt-desc">${desc}</span></span>`;
  b.addEventListener('click', () => { if (avail) onPick(e); });
  host.append(b);
}
function renderEngineOptions() {
  const host = $('engineOptions'); host.innerHTML = '';
  ['server', 'google', 'best', 'local'].forEach((e) => engineCard(e, host, setEngine));
  const badge = $('engineBadge');
  badge.textContent = engine() === 'google' ? (isIOS ? 'Apple' : 'Google') : engine() === 'server' ? (state.ui === 'am' ? 'ሰርቨር' : 'Server') : engine() === 'best' ? (state.ui === 'am' ? 'ምርጥ' : 'Best') : (state.ui === 'am' ? 'በስልኩ' : 'On-device');
  badge.dataset.kind = engine() === 'google' || engine() === 'server' ? 'cloud' : 'local';
}
$('engineBadge').addEventListener('click', () => showTab('settings'));

function warmLocal() {
  if (workerFailed) return;
  run({ type: 'warm', task: 'automatic-speech-recognition', model: LANGS[state.in].model }, [], true).then(() => { store.set('localReady', true); renderOffline(); }).catch(() => {});
}
function warmBest() {
  if (workerFailed) return;
  run({ type: 'warm-ctc', manifest: BEST_MANIFEST }, [], true).then(() => store.set('bestReady', true)).catch((err) => setChip(t('modelError'), 'err'));
}
function renderOffline() {
  $('offlineInfo').textContent = store.get('localReady', false) ? t('offlineReady') : t('offlineNot');
  if (mem && mem <= 4) $('offlineInfo').textContent += ' ' + t('lowMem', { mem });
}
$('warm').addEventListener('click', warmLocal);

async function serverHealth(cfg) {
  const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), 10000);
  try {
    const r = await fetch(cfg.url + '/v1/health', { headers: { Authorization: 'Bearer ' + cfg.key }, signal: ctl.signal, cache: 'no-store' });
    const j = await r.json();
    return j.auth ? { ok: true, msg: t('srvOk', { model: j.model }) } : { ok: false, msg: t('srvBadKey') };
  } catch { return { ok: false, msg: t('srvDown') }; } finally { clearTimeout(tm); }
}
async function serverTranscribe(samples) {
  const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), 180000);
  try {
    const r = await fetch(server.url + '/v1/transcribe', { method: 'POST', body: samples, signal: ctl.signal,
      headers: { Authorization: 'Bearer ' + server.key, 'Content-Type': 'application/octet-stream' } });
    const j = await r.json().catch(() => ({}));
    if (r.status === 401) throw new Error(t('srvBadKey'));
    if (!r.ok) throw new Error(j.error || 'HTTP ' + r.status);
    return j;
  } catch (err) { if (err.name === 'AbortError' || err instanceof TypeError) throw new Error(t('srvDown')); throw err; }
  finally { clearTimeout(tm); }
}
function renderServer() {
  $('srvUrl').value = server?.url || ''; $('srvKey').value = server?.key || ''; $('srvStatus').textContent = '';
}
$('srvSave').addEventListener('click', async () => {
  const url = $('srvUrl').value.trim().replace(/\/+$/, ''), key = $('srvKey').value.trim();
  if (!/^https?:\/\//.test(url) || !key) { $('srvStatus').textContent = t('srvDown'); return; }
  $('srvStatus').textContent = '…';
  const h = await serverHealth({ url, key });
  $('srvStatus').textContent = h.msg;
  if (h.ok) { server = { url, key }; store.set('server', server); setEngine('server'); }
});
$('srvForget').addEventListener('click', () => {
  server = null; store.del('server'); if (state.engine === 'server') { state.engine = null; store.del('engine'); }
  renderServer(); renderEngineOptions();
});

// ---------------------------------------------------------------- languages
function renderLangPair() {
  $('lpIn').textContent = LANGS[state.in].name; $('lpOut').textContent = OUT[state.out];
  $('trLang').textContent = OUT[state.out];
  fillSelect($('inLang'), Object.fromEntries(Object.entries(LANGS).map(([k, v]) => [k, v.name])), state.in);
  fillSelect($('outLang'), OUT, state.out);
  fillSelect($('tFrom'), OUT, store.get('tFrom', 'am')); fillSelect($('tTo'), OUT, store.get('tTo', 'en'));
  $('uiLang').value = state.ui;
  document.querySelectorAll('[data-mode]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.mode === state.mode)));
}
function fillSelect(sel, map, val) { sel.innerHTML = Object.entries(map).map(([k, v]) => `<option value="${k}">${v}</option>`).join(''); sel.value = val; }
function setIn(v) { state.in = v; store.set('in', v); renderLangPair(); renderEngineOptions(); } // engine availability depends on language
function setOut(v) { state.out = v; store.set('out', v); renderLangPair(); }
$('inLang').addEventListener('change', (e) => setIn(e.target.value));
$('outLang').addEventListener('change', (e) => setOut(e.target.value));
$('uiLang').addEventListener('change', (e) => { state.ui = e.target.value; store.set('ui', state.ui); applyLang(); });
document.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => { state.mode = b.dataset.mode; store.set('mode', state.mode); renderLangPair(); }));

function openLangSheet() {
  const build = (host, map, cur, pick) => { host.innerHTML = ''; for (const [k, v] of Object.entries(map)) { const b = document.createElement('button'); b.type = 'button'; b.textContent = v; b.setAttribute('aria-checked', String(k === cur)); b.onclick = () => { pick(k); openLangSheet(); }; host.append(b); } };
  build($('lsIn'), Object.fromEntries(Object.entries(LANGS).map(([k, v]) => [k, v.name])), state.in, setIn);
  build($('lsOut'), OUT, state.out, setOut);
  $('langSheet').hidden = false;
}
$('langPair').addEventListener('click', openLangSheet);
$('langDone').addEventListener('click', () => ($('langSheet').hidden = true));
$('langSheet').addEventListener('click', (e) => { if (e.target.id === 'langSheet') $('langSheet').hidden = true; });

// ---------------------------------------------------------------- result display
function showLive() { $('hero').hidden = true; $('live').hidden = false; }
function showHero() { $('hero').hidden = false; $('live').hidden = true; $('resultActions').hidden = true; $('kb').hidden = true; }
function resetResult() { $('text').value = ''; $('trBlock').hidden = true; $('trError').hidden = true; $('stats').textContent = ''; $('stats').classList.remove('warn'); }
function autosize() { const el = $('text'); el.style.height = 'auto'; el.style.height = Math.min(el.scrollHeight + 4, innerHeight * 0.46) + 'px'; }
$('text').addEventListener('input', autosize);

let current = null; // {id, text, translation, lang, engine, at}

// ---------------------------------------------------------------- recordings (IndexedDB, this device only)
// Every recording is saved BEFORE it is transcribed, so a failed upload, a crash or a reload never
// loses your voice. The newest 40 recordings are kept.
const clips = (() => {
  let dbp = null;
  const db = () => dbp || (dbp = new Promise((res, rej) => {
    const r = indexedDB.open('sema-audio', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('clips', { keyPath: 'id' });
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  }));
  const tx = async (mode, fn) => { const d = await db(); return new Promise((res, rej) => { const t = d.transaction('clips', mode); const st = t.objectStore('clips'); const out = fn(st); t.oncomplete = () => res(out?.result ?? out); t.onerror = () => rej(t.error); }); };
  return {
    async put(c) { try { await tx('readwrite', (st) => st.put(c)); await this.prune(); } catch {} },
    async get(id) { try { return await tx('readonly', (st) => st.get(id)); } catch { return null; } },
    async prune() {
      try {
        const all = await tx('readonly', (st) => st.getAll());
        all.sort((a, b) => b.at - a.at);
        const old = all.slice(40); if (old.length) await tx('readwrite', (st) => old.forEach((c) => st.delete(c.id)));
      } catch {}
    },
  };
})();
let lastClip = null; // {id, blob, type, noteId, at}
let player = null;
function showClipRow(failed) {
  $('clipRow').hidden = !lastClip;
  $('clipRetry').classList.toggle('attn', !!failed);
}
$('clipPlay').addEventListener('click', () => {
  if (!lastClip) return;
  if (player) { player.pause(); URL.revokeObjectURL(player.src); player = null; $('clipPlay').lastChild.textContent = t('playRec'); return; }
  player = new Audio(URL.createObjectURL(lastClip.blob));
  player.onended = () => { URL.revokeObjectURL(player.src); player = null; $('clipPlay').lastChild.textContent = t('playRec'); };
  player.play().catch(() => {}); $('clipPlay').lastChild.textContent = t('stopPlay');
});
$('clipRetry').addEventListener('click', () => { if (lastClip && !document.body.classList.contains('busy')) transcribeBlob(lastClip.blob, true); });

// The note being dictated: each recording is ADDED to it (it is never wiped by a new recording).
let noteBase = '';
function joinText(a, b) { a = (a || '').trim(); b = (b || '').trim(); return !a ? b : !b ? a : a + (/\n$/.test(a) ? '' : ' ') + b; }
function saveDraft() { store.set('draft', { text: $('text').value, id: current?.id || null }); }
function finishResult(text, meta, needsReview, eng, replaceLast) {
  text = text.trim();
  if (!text) { if (!$('text').value.trim()) showHero(); setStatus(t('empty')); return; }
  if (state.mode !== 'exact') text = clean(text, state.in);
  if (replaceLast && lastClip?.piece && $('text').value.endsWith(lastClip.piece)) noteBase = $('text').value.slice(0, -lastClip.piece.length);
  if (lastClip) lastClip.piece = text;
  text = joinText(noteBase, text);
  $('text').value = text; showLive(); autosize();
  $('stats').textContent = meta + (needsReview ? ' · ' + t('review') : ''); $('stats').classList.toggle('warn', !!needsReview);
  $('resultActions').hidden = false; setStatus(t('tapToSpeak'));
  if (current) { current.text = text; current.engine = eng; current.at = Date.now(); }
  else current = { id: Date.now().toString(36), text, translation: null, lang: state.in, engine: eng, at: Date.now() };
  if (lastClip) { current.clips = [...new Set([...(current.clips || []), lastClip.id])]; }
  saveHistory(current); saveDraft(); showClipRow(false);
  if (state.mode === 'translate') doTranslate();
}

/** Deterministic clean-up (same rules as the Android app); never changes words. */
function clean(text, lang) {
  const fillers = lang === 'am' ? ['እ', 'እም', 'ኧ', 'um', 'uh', 'uhm', 'erm', 'hmm'] : ['um', 'uh', 'uhm', 'erm', 'hmm'];
  let s = text;
  for (const f of fillers) s = s.replace(new RegExp(`(?:,\\s*)?(?<![\\p{L}\\p{M}])${f}(?![\\p{L}\\p{M}])[,\\s]*`, 'giu'), ' ');
  s = s.replace(/\s+([,.;:?!።፣፤፥፧])/g, '$1').replace(/\s+/g, ' ').trim();
  const geez = (s.match(/[ሀ-፿]/g) || []).length > (s.match(/[A-Za-z]/g) || []).length;
  if (geez) s = s.replace(/\.(?=\s|$)/g, '።').replace(/\?(?=\s|$)/g, '፧').replace(/,(?=\s)/g, '፣');
  if (s && /[\p{L}\p{N}]$/u.test(s)) s += geez ? '።' : '.';
  if (!geez) s = s.replace(/(^|[.!?]\s+)(\p{Ll})/gu, (m, a, b) => a + b.toUpperCase());
  return s;
}

// ---------------------------------------------------------------- recording: Google engine
// Android Chrome: continuous mode repeats earlier phrases inside later results (garbled, duplicated text)
// and a session ends by itself after a short pause. There we run one short session per phrase and
// restart it until the user taps stop, keeping the text of each finished phrase.
const isAndroid = /Android/i.test(navigator.userAgent);
let rec = null, recFinal = '', recSession = '', recCancelled = false, recError = '', recWanted = false, recQuiet = 0;
function startGoogle() {
  recFinal = ''; recSession = ''; recCancelled = false; recError = ''; recWanted = true; recQuiet = 0;
  noteBase = $('text').value; lastClip = null; showClipRow(false);
  showLive(); $('resultActions').hidden = true;
  if (!googleSession()) { recWanted = false; return; }
  recordingUi(true, t('liveListening'));
}
function stopGoogle(cancel) {
  recWanted = false; if (cancel) recCancelled = true;
  if (rec) { try { cancel ? rec.abort() : rec.stop(); } catch { googleDone(); } } else googleDone();
}
function googleSession() {
  const r = new SR(); r.lang = LANGS[state.in].bcp; r.continuous = !(isAndroid || isIOS); r.interimResults = true; r.maxAlternatives = 1;
  recSession = '';
  r.onresult = (e) => {
    let txt = '';
    if (r.continuous) { for (let i = 0; i < e.results.length; i++) txt += e.results[i][0].transcript + ' '; }
    else txt = e.results[e.results.length - 1][0].transcript; // one phrase per session: the last result is the whole phrase
    recSession = txt.replace(/\s+/g, ' ').trim();
    $('text').value = joinText(noteBase, (recFinal + ' ' + recSession).trim()); autosize();
  };
  r.onerror = (e) => {
    if (e.error === 'aborted') return;
    if (e.error === 'no-speech' && recWanted) return; // a pause: the next session keeps listening
    recError = e.error; recWanted = false;
    const map = { 'not-allowed': t('micDenied'), 'service-not-allowed': t('googleUnavail'), 'language-not-supported': t('googleLang'),
      network: t('googleOffline'), 'audio-capture': t('micBusy'), 'no-speech': t('empty') };
    setStatus(map[e.error] || `Error: ${e.error}`);
    if (['service-not-allowed', 'language-not-supported', 'network'].includes(e.error)) banner(map[e.error], t('switchEngine'), () => showTab('settings'));
  };
  r.onend = () => {
    if (rec !== r) return;
    rec = null;
    if (recSession) { recFinal = (recFinal + ' ' + recSession).trim(); recSession = ''; recQuiet = 0; } else recQuiet++;
    // keep listening across pauses; give up after about 3 silent sessions so it never runs forever
    if (recWanted && !recCancelled && !recError && recQuiet < 3 && googleSession()) return;
    googleDone();
  };
  try { r.start(); } catch (err) { setStatus(describe(err)); return null; }
  rec = r; return r;
}
function googleDone() {
  recWanted = false; rec = null; recordingUi(false);
  if (recCancelled) { $('text').value = noteBase; if (!noteBase.trim()) showHero(); else { $('resultActions').hidden = false; autosize(); } setStatus(t('tapToSpeak')); return; }
  const text = (recFinal + ' ' + recSession).replace(/\s+/g, ' ').trim(); recSession = '';
  $('text').value = noteBase;
  if (!text) { if (!noteBase.trim()) showHero(); else $('resultActions').hidden = false; if (!recError) setStatus(t('empty')); return; }
  finishResult(text, `${isIOS ? 'Apple' : 'Google'} · ${LANGS[state.in].bcp} · online`, false, 'google');
}

// ---------------------------------------------------------------- recording: on-device engine
let media = null, chunks = [], stream = null, actx = null, levelTimer = 0, mediaCancelled = false;
async function startLocal() {
  if (workerFailed) { worker = null; workerFailed = false; }
  // Start loading the model now (in the worker) so it is ready by the time you stop talking.
  if (engine() === 'server') { /* nothing to load: the model runs on your server */ }
  else if (engine() === 'best') warmBest();
  else run({ type: 'warm', task: 'automatic-speech-recognition', model: LANGS[state.in].model }, [], true).then(() => { store.set('localReady', true); }).catch(() => {});
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true } }); }
  catch { setStatus(t('micDenied')); return; }
  chunks = []; mediaCancelled = false;
  media = new MediaRecorder(stream);
  media.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
  media.onstop = onLocalStop;
  media.start(250);
  try {
    actx = new AudioContext(); const an = actx.createAnalyser(); an.fftSize = 512; actx.createMediaStreamSource(stream).connect(an);
    const buf = new Uint8Array(an.fftSize);
    levelTimer = setInterval(() => { an.getByteTimeDomainData(buf); let s = 0; for (const v of buf) { const d = (v - 128) / 128; s += d * d; } document.documentElement.style.setProperty('--lvl', Math.min(1, Math.sqrt(s / buf.length) * 4).toFixed(3)); }, 80);
  } catch {}
  noteBase = $('text').value;
  if (!noteBase.trim()) { resetResult(); showHero(); }
  recordingUi(true, t('listening'));
  const m = media; setTimeout(() => { if (m.state === 'recording') m.stop(); }, engine() === 'best' || engine() === 'server' ? 120000 : 30000); // Whisper: one 30 s window
}
function stopLocalStream() {
  clearInterval(levelTimer); document.documentElement.style.setProperty('--lvl', '0');
  stream?.getTracks().forEach((tr) => tr.stop()); stream = null; actx?.close().catch(() => {}); actx = null;
}
async function onLocalStop() {
  stopLocalStream(); recordingUi(false);
  if (mediaCancelled) { setStatus(t('tapToSpeak')); return; }
  const blob = new Blob(chunks, { type: media.mimeType }); chunks = [];
  if (blob.size < 2000) { setStatus(t('empty')); return; }
  lastClip = { id: 'c' + Date.now().toString(36), blob, type: blob.type, noteId: current?.id || null, at: Date.now() };
  await clips.put({ ...lastClip });
  await transcribeBlob(blob, false);
}
async function transcribeBlob(blob, retry) {
  document.body.classList.add('busy'); setStatus(t('transcribing'));
  if (!$('text').value.trim()) { showLive(); $('resultActions').hidden = true; }
  try {
    const pcm = await decode16k(blob);
    if (pcm.length < 16000 * 0.3) { setStatus(t('empty')); return; }
    const samples = pcm.slice(0, Math.min(pcm.length, 480000)); // always a copy: safe to transfer
    const audioMs = Math.round(samples.length / 16);
    if (engine() === 'server' && state.in === 'am') {
      setStatus(t('srvBusy'));
      const all = pcm.slice(0, Math.min(pcm.length, 16000 * 120));
      const r = await serverTranscribe(all);
      finishResult(r.text, `${(r.ms / 1000).toFixed(1)} s · Sema server · ${r.model || 'Ethio-ASR'}`, false, 'server', retry);
      return;
    }
    if (engine() === 'best' && state.in === 'am') {
      const all = pcm.slice(0, Math.min(pcm.length, 16000 * 120));
      const r = await run({ type: 'ctc', manifest: BEST_MANIFEST, samples: all }, [all.buffer]);
      finishResult(r.text, `${(r.ms / 1000).toFixed(1)} s · on-device · Ethio-ASR (best)`, false, 'best', retry);
      return;
    }
    const r = await run({ type: 'asr', model: LANGS[state.in].model, samples, language: LANGS[state.in].whisper }, [samples.buffer]);
    const g = guard(r.text.replace(/\s+/g, ' '), audioMs);
    finishResult(g.text, `${(r.ms / 1000).toFixed(1)} s · on-device · ${LANGS[state.in].model.split('/').pop()}`, g.needsReview, 'local', retry);
  } catch (err) {
    setStatus(describe(err) + ' ' + t('recSaved'));
    showLive(); if ($('text').value.trim()) $('resultActions').hidden = false;
    showClipRow(true);
  }
  finally { document.body.classList.remove('busy'); }
}
async function decode16k(blob) {
  const ab = await blob.arrayBuffer();
  const tmp = new (window.AudioContext || window.webkitAudioContext)();
  let decoded;
  try { decoded = await tmp.decodeAudioData(ab); } finally { tmp.close().catch(() => {}); }
  const off = new OfflineAudioContext(1, Math.ceil(decoded.duration * 16000), 16000);
  const src = off.createBufferSource(); src.buffer = decoded; src.connect(off.destination); src.start();
  return (await off.startRendering()).getChannelData(0);
}

// ---------------------------------------------------------------- mic control
let timerInt = 0, recStart = 0;
function recActive() { return !!rec || media?.state === 'recording'; }
function recordingUi(on, msg) {
  $('mic').classList.toggle('rec', on); document.body.classList.toggle('recording', on);
  $('mic').setAttribute('aria-label', on ? 'Stop' : 'Start dictation'); $('cancel').hidden = !on; $('timer').hidden = !on;
  clearInterval(timerInt);
  if (on) { recStart = Date.now(); $('timer').textContent = '0:00'; timerInt = setInterval(() => { const s = Math.floor((Date.now() - recStart) / 1000); $('timer').textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; }, 500); setStatus(msg); }
}
$('mic').addEventListener('click', () => {
  if (engine() === 'google') { if (recWanted || rec) stopGoogle(false); else startGoogle(); return; }
  if (media?.state === 'recording') media.stop(); else startLocal();
});
$('cancel').addEventListener('click', () => {
  if (recWanted || rec) { stopGoogle(true); return; }
  if (media?.state === 'recording') { mediaCancelled = true; media.stop(); }
});

// ---------------------------------------------------------------- result actions
$('copy').addEventListener('click', async () => {
  const s = $('text').value + ($('trBlock').hidden ? '' : '\n' + $('translation').textContent);
  try { await navigator.clipboard.writeText(s); toast(t('copied')); } catch { $('text').select(); }
});
$('share').addEventListener('click', async () => {
  const s = $('text').value + ($('trBlock').hidden ? '' : '\n' + $('translation').textContent);
  if (navigator.share) { try { await navigator.share({ text: s }); } catch {} } else { await navigator.clipboard.writeText(s).catch(() => {}); toast(t('copied')); }
});
$('translate').addEventListener('click', () => doTranslate());
$('discard').addEventListener('click', () => { if (current) { current.text = $('text').value; saveHistory(current); } resetResult(); showHero(); current = null; lastClip = null; noteBase = ''; showClipRow(false); store.del('draft'); setStatus(t('tapToSpeak')); });
$('speak').addEventListener('click', () => speakText($('trBlock').hidden ? $('text').value : $('translation').textContent, $('trBlock').hidden ? state.in : state.out));
$('text').addEventListener('change', () => { if (current) { current.text = $('text').value; saveHistory(current); } saveDraft(); });
$('text').addEventListener('input', () => saveDraft());

async function doTranslate() {
  const text = $('text').value.trim(); if (!text) return;
  const m = trModel(state.in, state.out);
  $('trError').hidden = true;
  if (!m) { $('trError').textContent = t('noTr'); $('trError').hidden = false; return; }
  document.body.classList.add('busy'); setStatus(t('translating'));
  try {
    const r = await run({ type: 'translate', model: m.id, text, prefix: m.prefix });
    $('translation').textContent = r.text; $('trBlock').hidden = false;
    if (current) { current.translation = r.text; current.out = state.out; saveHistory(current); }
    setStatus(t('tapToSpeak'));
  } catch (err) { $('trError').textContent = describe(err); $('trError').hidden = false; setStatus(t('tapToSpeak')); }
  finally { document.body.classList.remove('busy'); }
}

function speakText(text, lang) {
  const code = (LANGS[lang]?.bcp || lang).slice(0, 2);
  const voices = speechSynthesis.getVoices().filter((v) => v.lang.toLowerCase().startsWith(code));
  if (!voices.length) { toast(t('noVoice')); return; }
  const u = new SpeechSynthesisUtterance(text); u.voice = voices[0]; u.lang = voices[0].lang; speechSynthesis.speak(u);
}

// keyboard
attachGeezKeyboard($('text'), $('kb'), { space: state.ui === 'am' ? 'ክፍተት' : 'space' });
$('kbToggle').addEventListener('click', () => { $('kb').hidden = !$('kb').hidden; if (!$('kb').hidden) $('text').focus({ preventScroll: true }); });

// ---------------------------------------------------------------- history (local only)
function loadHistory() { return store.get('history', []); }
function saveHistory(item) {
  const list = loadHistory().filter((x) => x.id !== item.id); list.unshift(item);
  store.set('history', list.slice(0, 300));
}
function renderHistory() {
  const q = $('historySearch').value.trim().toLowerCase();
  const list = loadHistory().filter((x) => !q || x.text.toLowerCase().includes(q) || (x.translation || '').toLowerCase().includes(q));
  const host = $('historyList'); host.innerHTML = '';
  $('historyEmpty').hidden = list.length > 0;
  let fmt; try { fmt = new Intl.DateTimeFormat(state.ui === 'am' ? 'am-ET' : undefined, { dateStyle: 'medium', timeStyle: 'short' }); } catch { fmt = new Intl.DateTimeFormat(); }
  for (const x of list) {
    const card = document.createElement('article'); card.className = 'note-card';
    const head = document.createElement('div'); head.className = 'note-head';
    head.innerHTML = `<span class="tag">${LANGS[x.lang]?.name || x.lang}</span><span class="tag">${x.engine === 'google' ? 'Google' : 'On-device'}</span><span>${fmt.format(x.at)}</span>`;
    const p = document.createElement('p'); p.className = 'note-text'; p.textContent = x.text;
    card.append(head, p);
    if (x.translation) { const tr = document.createElement('p'); tr.className = 'note-tr'; tr.textContent = x.translation; card.append(tr); }
    const acts = document.createElement('div'); acts.className = 'note-actions';
    const mk = (label, fn) => { const b = document.createElement('button'); b.type = 'button'; b.textContent = label; b.onclick = fn; acts.append(b); };
    mk(t('copy'), async () => { await navigator.clipboard.writeText(x.text + (x.translation ? '\n' + x.translation : '')).catch(() => {}); toast(t('copied')); });
    mk(t('share'), async () => { if (navigator.share) navigator.share({ text: x.text }).catch(() => {}); });
    mk(t('listen'), () => speakText(x.text, x.lang));
    if (x.clips?.length) mk(t('playRec'), async () => {
      const c = await clips.get(x.clips[x.clips.length - 1]); if (!c) { toast('—'); return; }
      const a = new Audio(URL.createObjectURL(c.blob)); a.onended = () => URL.revokeObjectURL(a.src); a.play().catch(() => {});
    });
    mk(t('clear'), () => { store.set('history', loadHistory().filter((y) => y.id !== x.id)); renderHistory(); toast(t('deleted')); });
    card.append(acts); host.append(card);
  }
}
$('historySearch').addEventListener('input', renderHistory);
let clearArmed = false;
$('clearHistory').addEventListener('click', () => {
  if (!clearArmed) { clearArmed = true; toast(t('confirmClear')); setTimeout(() => (clearArmed = false), 3000); return; }
  store.set('history', []); clearArmed = false; toast(t('deleted')); renderHistory();
});

// ---------------------------------------------------------------- translate tab
$('tFrom').addEventListener('change', (e) => store.set('tFrom', e.target.value));
$('tTo').addEventListener('change', (e) => store.set('tTo', e.target.value));
$('tSwap').addEventListener('click', () => { const a = $('tFrom').value; $('tFrom').value = $('tTo').value; $('tTo').value = a; store.set('tFrom', $('tFrom').value); store.set('tTo', $('tTo').value); });
$('tGo').addEventListener('click', async () => {
  const text = $('tInput').value.trim(); if (!text) return;
  const m = trModel($('tFrom').value, $('tTo').value);
  $('tOutCard').hidden = false;
  if (!m) { $('tOut').textContent = t('noTr'); $('tMeta').textContent = ''; return; }
  $('tOut').textContent = '…'; $('tMeta').textContent = t('translating');
  try { const r = await run({ type: 'translate', model: m.id, text, prefix: m.prefix }); $('tOut').textContent = r.text; $('tMeta').textContent = `${(r.ms / 1000).toFixed(1)} s · on-device · ${m.id.split('/').pop()}`; }
  catch (err) { $('tOut').textContent = describe(err); $('tMeta').textContent = ''; }
});
$('tCopy').addEventListener('click', async () => { await navigator.clipboard.writeText($('tOut').textContent).catch(() => {}); toast(t('copied')); });
$('tListen').addEventListener('click', () => speakText($('tOut').textContent, $('tTo').value));
attachGeezKeyboard($('tInput'), $('tKbHost'), { space: 'ክፍተት' });
$('tKb').addEventListener('click', () => ($('tKbHost').hidden = !$('tKbHost').hidden));

// ---------------------------------------------------------------- diagnostics, install, banner
function renderDiag() {
  const c = store.get('crashes', []);
  $('diag').textContent = [`engine=${engine()} speechApi=${!!SR} phone=${isPhone} deviceMemory=${mem || '?'}GB`,
    `cores=${navigator.hardwareConcurrency || '?'} online=${navigator.onLine}`,
    ...c.map((x) => `crash during ${x.s} at ${new Date(x.at).toLocaleString()}`)].join('\n');
}
let installEvt = null;
addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installEvt = e; $('install').hidden = false; });
$('install').addEventListener('click', () => installEvt?.prompt());
function banner(text, actionLabel, action) {
  $('bannerText').textContent = text; $('bannerAction').textContent = actionLabel; $('banner').hidden = false;
  $('bannerAction').onclick = () => { action(); $('banner').hidden = true; };
}
$('bannerClose').addEventListener('click', () => ($('banner').hidden = true));

// ---------------------------------------------------------------- first run
function openWelcome() {
  const host = $('welcomeOptions');
  const render = () => { host.innerHTML = ''; ['server', 'google', 'best', 'local'].forEach((e) => engineCard(e, host, (x) => { state.engine = x; render(); })); };
  render();
  $('welcome').hidden = false;
}
$('welcomeGo').addEventListener('click', () => { setEngine(engine()); store.set('welcomed', true); $('welcome').hidden = true; });

// ---------------------------------------------------------------- start
applyLang();
showTab('speak');
showHero();
(() => { // restore the note you were working on (after a reload or a crash)
  const d = store.get('draft', null);
  if (!d || !d.text || !d.text.trim()) return;
  $('text').value = d.text; current = loadHistory().find((x) => x.id === d.id) || null;
  showLive(); $('resultActions').hidden = false; requestAnimationFrame(autosize);
  if (current?.clips?.length) clips.get(current.clips[current.clips.length - 1]).then((c) => { if (c) { lastClip = c; showClipRow(false); } });
  toast(t('restored'));
})();
if (serverLinked) { store.set('welcomed', true); toast(t('srvLinked')); serverHealth(server).then((h) => { if (!h.ok) banner(h.msg, t('dismiss'), () => {}); }); }
else if (!store.get('welcomed', false)) openWelcome();
if (crashed && ['loading', 'transcribing'].includes(crashed.s)) {
  banner(t('crashed', { stage: t('stage_' + crashed.s) }), SR ? t('useGoogle') : t('dismiss'), () => { if (SR) setEngine('google'); });
}
// Desktop only: prepare the on-device model in the background when it is the chosen engine.
if (engine() === 'local' && !isPhone && !crashed) (window.requestIdleCallback || ((f) => setTimeout(f, 1500)))(warmLocal);
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js');
