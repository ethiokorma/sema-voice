// On-screen Ge'ez keyboard for the PWA.
// Keys are Ethiopic consonant families (first form ሀ ለ ሐ …). Tapping one inserts it and shows
// its seven vowel forms (ሀ ሁ ሂ ሃ ሄ ህ ሆ) above the rows; tapping a form replaces the letter.
// "ABC" switches to a Latin QWERTY for names and English words.

// Each family = base code point; forms are base+0..6 (ä u i a ē ə o).
const GEEZ_ROWS = [
  [0x1200, 0x1208, 0x1210, 0x1218, 0x1220, 0x1228, 0x1230, 0x1238, 0x1240, 0x1260], // ሀ ለ ሐ መ ሠ ረ ሰ ሸ ቀ በ
  [0x1270, 0x1278, 0x1280, 0x1290, 0x1298, 0x12A0, 0x12A8, 0x12B8, 0x12C8, 0x12D0], // ተ ቸ ኀ ነ ኘ አ ከ ኸ ወ ዐ
  [0x12D8, 0x12E0, 0x12E8, 0x12F0, 0x1300, 0x1308, 0x1320, 0x1328, 0x1330, 0x1338], // ዘ ዠ የ ደ ጀ ገ ጠ ጨ ጰ ጸ
  [0x1340, 0x1348, 0x1350, 0x1268],                                                 // ፀ ፈ ፐ ቨ
];
const PUNCT = ['።', '፣', '፧', '፤', '፥', '፦'];
const LATIN_ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];

/** Phonetic composer kept for programmatic use (same rules as the Android GeezComposer). */
export class GeezComposer {
  constructor() { this.reset(); }
  reset() { this.family = null; this.key = null; this.last = 0; }
  type(ch) {
    const C = GeezComposer.CONSONANTS, D = GeezComposer.DIGRAPHS, V = GeezComposer.VOWELS;
    const di = this.key != null ? D[this.key + ch] : undefined;
    if (di !== undefined) { this.family = di; this.key = null; return this.replace(String.fromCodePoint(di + 5)); }
    if (ch in V) { const fam = this.family; this.family = null; this.key = null; return fam != null ? this.replace(String.fromCodePoint(fam + V[ch])) : this.insert(String.fromCodePoint(0x12A0 + V[ch])); }
    if (ch in C) { this.family = C[ch]; this.key = ch; return this.insert(String.fromCodePoint(C[ch] + 5)); }
    this.reset(); return { deleteBefore: 0, insert: ch };
  }
  replace(t) { const d = this.last; this.last = t.length; return { deleteBefore: d, insert: t }; }
  insert(t) { this.last = t.length; return { deleteBefore: 0, insert: t }; }
}
GeezComposer.CONSONANTS = { h: 0x1200, l: 0x1208, H: 0x1210, m: 0x1218, s: 0x1230, r: 0x1228, x: 0x1238, q: 0x1240, b: 0x1260, v: 0x1268, t: 0x1270, c: 0x1278, n: 0x1290, N: 0x1298, k: 0x12A8, K: 0x12B8, w: 0x12C8, z: 0x12D8, Z: 0x12E0, y: 0x12E8, d: 0x12F0, j: 0x1300, g: 0x1308, T: 0x1320, C: 0x1328, P: 0x1330, S: 0x1338, f: 0x1348, p: 0x1350 };
GeezComposer.DIGRAPHS = { sh: 0x1238, ch: 0x1278, kh: 0x12B8, zh: 0x12E0, ny: 0x1298, ts: 0x1338 };
GeezComposer.VOWELS = { e: 0, u: 1, i: 2, a: 3, E: 4, o: 6 };

/** Builds the keyboard inside `container` and wires it to `textarea`. */
export function attachGeezKeyboard(textarea, container, labels = {}) {
  let geez = true, shift = false, pendingFamily = null, pendingPos = -1;
  container.className = 'gkb';

  const fire = () => textarea.dispatchEvent(new Event('input', { bubbles: true }));
  const insertAt = (text) => { textarea.setRangeText(text, textarea.selectionStart, textarea.selectionEnd, 'end'); fire(); textarea.focus({ preventScroll: true }); };
  const key = (label, onClick, cls = '') => { const b = document.createElement('button'); b.type = 'button'; b.textContent = label; b.className = 'gk ' + cls; b.onpointerdown = (e) => { e.preventDefault(); onClick(); }; return b; };
  const row = (cls = '') => { const r = document.createElement('div'); r.className = 'gkrow ' + cls; return r; };

  function tapFamily(base) {
    insertAt(String.fromCodePoint(base));            // first form (ä)
    pendingFamily = base; pendingPos = textarea.selectionStart; render();
  }
  function pickForm(cp) {
    if (pendingFamily != null && textarea.selectionStart === pendingPos && pendingPos > 0) {
      textarea.setRangeText(String.fromCodePoint(cp), pendingPos - 1, pendingPos, 'end'); fire(); textarea.focus({ preventScroll: true });
    } else insertAt(String.fromCodePoint(cp));
    pendingFamily = null; render();
  }
  function clearPending() { pendingFamily = null; }

  function render() {
    container.innerHTML = '';
    const top = row();
    top.append(key(geez ? 'ABC' : 'ፊደል', () => { geez = !geez; clearPending(); render(); }, 'alt'));
    if (geez && pendingFamily != null) {
      for (let v = 0; v < 7; v++) { const cp = pendingFamily + v; top.append(key(String.fromCodePoint(cp), () => pickForm(cp), 'form')); }
    } else {
      for (const p of PUNCT) top.append(key(p, () => { clearPending(); insertAt(p); }));
    }
    container.append(top);
    if (geez) {
      GEEZ_ROWS.forEach((fams, i) => {
        const r = row();
        for (const base of fams) r.append(key(String.fromCodePoint(base), () => tapFamily(base)));
        if (i === GEEZ_ROWS.length - 1) {
          r.append(key('⌫', backspace, 'alt'));
          r.append(key(labels.space || 'ክፍተት', () => { clearPending(); insertAt(' '); }, 'space'));
          r.append(key('↵', () => { clearPending(); insertAt('\n'); }, 'alt'));
        }
        container.append(r);
      });
    } else {
      LATIN_ROWS.forEach((chars, i) => {
        const r = row();
        if (i === 2) r.append(key('⇧', () => { shift = !shift; render(); }, 'alt' + (shift ? ' on' : '')));
        for (const ch of chars) { const c = shift ? ch.toUpperCase() : ch; r.append(key(c, () => { insertAt(c); if (shift) { shift = false; render(); } })); }
        if (i === 2) r.append(key('⌫', backspace, 'alt'));
        container.append(r);
      });
      const b = row(); b.append(key(labels.space || 'space', () => insertAt(' '), 'space')); b.append(key('↵', () => insertAt('\n'), 'alt')); container.append(b);
    }
  }
  function backspace() {
    clearPending();
    const s = textarea.selectionStart, e = textarea.selectionEnd;
    if (s !== e) textarea.setRangeText('', s, e, 'end'); else if (s > 0) textarea.setRangeText('', s - 1, s, 'end');
    fire(); textarea.focus({ preventScroll: true });
    render();
  }
  textarea.addEventListener('pointerdown', () => { if (pendingFamily != null) { pendingFamily = null; render(); } });
  render();
  return { setVisible(v) { container.hidden = !v; } };
}
