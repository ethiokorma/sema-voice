// Sema Voice PWA service worker: app shell offline + cache-first for model files.
// transformers.js caches Hugging Face model files itself (Cache API, "transformers-cache");
// this worker covers the shell, the CDN script and the locally hosted Amharic model.
const SHELL = 'sema-shell-v9';
const RUNTIME = 'sema-runtime-v3';
const SHELL_FILES = ['./', './index.html', './styles.css', './app.js', './keyboard.js', './worker.js', './guard.js', './ctc.js', './manifest.webmanifest', './icon.svg'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(SHELL_FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => ![SHELL, RUNTIME].includes(k)).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

// Cross-origin isolation: GitHub Pages cannot send COOP/COEP headers, so the service worker adds
// them to same-origin responses. That enables SharedArrayBuffer, which multi-threaded
// WebAssembly (ONNX Runtime Web) needs: about N-times faster on an N-core device.
function withCOI(r) {
  if (!r || r.status === 0 || r.type === 'opaque' || r.type === 'opaqueredirect') return r;
  const h = new Headers(r.headers);
  h.set('Cross-Origin-Opener-Policy', 'same-origin');
  h.set('Cross-Origin-Embedder-Policy', 'credentialless');
  h.set('Cross-Origin-Resource-Policy', 'cross-origin');
  return new Response(r.body, { status: r.status, statusText: r.statusText, headers: h });
}

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  const sameOrigin = url.origin === location.origin;
  const isShell = sameOrigin && !url.pathname.includes('/models/');
  const isCdn = url.hostname === 'cdn.jsdelivr.net' || url.hostname === 'huggingface.co' || url.hostname.endsWith('.hf.co');
  if (isShell) {
    // Network first for the shell so updates arrive; fall back to cache offline.
    e.respondWith(fetch(e.request).then((r) => { const copy = r.clone(); caches.open(SHELL).then((c) => c.put(e.request, copy)); return withCOI(r); })
      .catch(() => caches.match(e.request).then(withCOI)));
  } else if (sameOrigin) {
    // Model files: stream straight through (the worker keeps its own model cache).
    e.respondWith(fetch(e.request).then(withCOI));
  } else if (isCdn) {
    // Small CDN files (scripts, JSON, wasm) are cached; large model weights are not duplicated here.
    e.respondWith(caches.open(RUNTIME).then(async (c) => {
      const hit = await c.match(e.request);
      if (hit) return hit;
      const r = await fetch(e.request);
      const len = Number(r.headers.get('content-length') || 0);
      if (r.ok && r.status === 200 && len > 0 && len < 16 * 1024 * 1024) c.put(e.request, r.clone());
      return r;
    }));
  }
});
