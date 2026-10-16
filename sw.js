// Sema Voice PWA service worker: app shell offline + cache-first for model files.
// transformers.js caches Hugging Face model files itself (Cache API, "transformers-cache");
// this worker covers the shell, the CDN script and the locally hosted Amharic model.
const SHELL = 'sema-shell-v8';
const RUNTIME = 'sema-runtime-v3';
const SHELL_FILES = ['./', './index.html', './styles.css', './app.js', './keyboard.js', './worker.js', './guard.js', './manifest.webmanifest', './icon.svg'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(SHELL_FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => ![SHELL, RUNTIME].includes(k)).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  const isShell = url.origin === location.origin && !url.pathname.includes('/models/');
  const isModelOrCdn = url.pathname.includes('/models/') || url.hostname === 'cdn.jsdelivr.net' || url.hostname === 'huggingface.co' || url.hostname.endsWith('.hf.co');
  if (isShell) {
    // Network first for the shell so updates arrive; fall back to cache offline.
    e.respondWith(fetch(e.request).then((r) => { const copy = r.clone(); caches.open(SHELL).then((c) => c.put(e.request, copy)); return r; }).catch(() => caches.match(e.request)));
  } else if (isModelOrCdn) {
    // Only small files (scripts, JSON) are cached here. Model weights are cached by
    // transformers.js itself; cloning 40+ MB responses here doubled memory use on phones.
    e.respondWith(caches.open(RUNTIME).then(async (c) => {
      const hit = await c.match(e.request);
      if (hit) return hit;
      const r = await fetch(e.request);
      const len = Number(r.headers.get('content-length') || 0);
      if (r.ok && r.status === 200 && len > 0 && len < 8 * 1024 * 1024) c.put(e.request, r.clone());
      return r;
    }));
  }
});
