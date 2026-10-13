// On-screen phonetic Ge'ez keyboard for the PWA — JS port of the Android GeezComposer.
// Latin keys compose Ethiopic syllables: s → ስ, se → ሰ, sa → ሳ, sh → ሽ, selam → ሰላም.
// Families occupy 8 code points in vowel order ä,u,i,a,ē,ə(bare),o,(wa).

const CONSONANTS = { h: 0x1200, l: 0x1208, H: 0x1210, m: 0x1218, s: 0x1230, r: 0x1228, x: 0x1238, q: 0x1240, b: 0x1260, v: 0x1268,
  t: 0x1270, c: 0x1278, n: 0x1290, N: 0x1298, k: 0x12A8, K: 0x12B8, w: 0x12C8, z: 0x12D8, Z: 0x12E0, y: 0x12E8, d: 0x12F0,
  j: 0x1300, g: 0x1308, T: 0x1320, C: 0x1328, P: 0x1330, S: 0x1338, f: 0x1348, p: 0x1350 };
const DIGRAPHS = { sh: 0x1238, ch: 0x1278, kh: 0x12B8, zh: 0x12E0, ny: 0x1298, ts: 0x1338 };
const VOWELS = { e: 0, u: 1, i: 2, a: 3, E: 4, o: 6 };
const BARE = 5, GLOTTAL = 0x12A0;

export class GeezComposer {
  constructor() { this.reset(); }
  reset() { this.family = null; this.key = null; this.last = 0; }
  /** Returns { deleteBefore, insert }. */
  type(ch) {
    const di = this.key != null ? DIGRAPHS[this.key + ch] : undefined;
    if (di !== undefined) { this.family = di; this.key = null; return this.replace(this.syl(di, BARE)); }
    if (ch in VOWELS) {
      const fam = this.family; this.family = null; this.key = null;
      return fam != null ? this.replace(this.syl(fam, VOWELS[ch])) : this.insert(this.syl(GLOTTAL, VOWELS[ch]));
    }
    if (ch in CONSONANTS) { this.family = CONSONANTS[ch]; this.key = ch; return this.insert(this.syl(CONSONANTS[ch], BARE)); }
    this.reset();
    return { deleteBefore: 0, insert: ch };
  }
  syl(fam, v) { return String.fromCodePoint(fam + v); }
  replace(t) { const d = this.last; this.last = t.length; return { deleteBefore: d, insert: t }; }
  insert(t) { this.last = t.length; return { deleteBefore: 0, insert: t }; }
}

const ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
const PUNCT = ['።', '፣', '፧', '፤', '.', ',', '?'];

/** Builds the keyboard inside `container` and wires it to `textarea`. Returns { setVisible }. */
export function attachGeezKeyboard(textarea, container, labels = {}) {
  const composer = new GeezComposer();
  let shift = false, geez = true;
  container.innerHTML = '';
  container.className = 'gkb';

  const insertEdit = ({ deleteBefore, insert }) => {
    const start = textarea.selectionStart, end = textarea.selectionEnd;
    const from = Math.max(0, start - (start === end ? deleteBefore : 0));
    textarea.setRangeText(insert, from, end, 'end');
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
  };
  const typeKey = (ch) => {
    const c = shift ? ch.toUpperCase() : ch;
    if (geez && c.length === 1) insertEdit(composer.type(c)); else { composer.reset(); insertEdit({ deleteBefore: 0, insert: c }); }
    if (shift) { shift = false; render(); }
    textarea.focus({ preventScroll: true });
  };
  const key = (label, onClick, cls = '') => { const b = document.createElement('button'); b.type = 'button'; b.textContent = label; b.className = 'gk ' + cls; b.onpointerdown = (e) => { e.preventDefault(); onClick(); }; return b; };

  function render() {
    container.innerHTML = '';
    const top = document.createElement('div'); top.className = 'gkrow';
    top.append(key(geez ? 'ፊደል' : 'ABC', () => { geez = !geez; composer.reset(); render(); }, 'alt'));
    for (const p of PUNCT) top.append(key(p, () => { composer.reset(); insertEdit({ deleteBefore: 0, insert: p }); }));
    container.append(top);
    ROWS.forEach((row, i) => {
      const r = document.createElement('div'); r.className = 'gkrow';
      if (i === 2) r.append(key('⇧', () => { shift = !shift; render(); }, 'alt' + (shift ? ' on' : '')));
      for (const ch of row) r.append(key(shift ? ch.toUpperCase() : ch, () => typeKey(ch)));
      if (i === 2) r.append(key('⌫', () => { composer.reset(); const s = textarea.selectionStart, e = textarea.selectionEnd; if (s !== e) textarea.setRangeText('', s, e, 'end'); else if (s > 0) textarea.setRangeText('', s - 1, s, 'end'); textarea.dispatchEvent(new Event('input', { bubbles: true })); }, 'alt'));
      container.append(r);
    });
    const bottom = document.createElement('div'); bottom.className = 'gkrow';
    bottom.append(key(labels.space || 'space', () => { composer.reset(); insertEdit({ deleteBefore: 0, insert: ' ' }); }, 'space'));
    bottom.append(key('↵', () => { composer.reset(); insertEdit({ deleteBefore: 0, insert: '\n' }); }, 'alt'));
    container.append(bottom);
  }
  // Moving the caret by hand ends the current syllable composition.
  textarea.addEventListener('pointerdown', () => composer.reset());
  textarea.addEventListener('keydown', () => composer.reset());
  render();
  return { setVisible(v) { container.hidden = !v; } };
}
