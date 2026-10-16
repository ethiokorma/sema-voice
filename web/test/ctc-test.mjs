// node web/test/ctc-test.mjs  — compares the JS front-end with the Python reference fixture
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { extractFeatures, ctcDecode } from '../ctc.js';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../core/speech/src/test/resources/w2vbert');
const f32 = (n) => { const b = readFileSync(path.join(root, n)); return new Float32Array(b.buffer, b.byteOffset, b.byteLength / 4); };
const input = f32('input.f32'), expected = f32('features.f32');
const { data, frames, dim } = extractFeatures(input);
let max = 0, sum = 0; for (let i = 0; i < data.length; i++) { const e = Math.abs(data[i] - expected[i]); sum += e; if (e > max) max = e; }
const mean = sum / data.length;
console.log({ frames, dim, len: data.length, expectedLen: expected.length, meanAbsErr: mean, maxAbsErr: max });
const vocab = ['|', 'ሰ', 'ላ', 'ም', '[PAD]', '[AMH]'];
const seq = [1, 1, 4, 2, 3, 3, 0, 0, 1, 5]; const logits = new Float32Array(seq.length * 6); seq.forEach((id, t) => (logits[t * 6 + id] = 1));
const dec = ctcDecode(logits, seq.length, 6, vocab, 4);
const ok = frames === 74 && data.length === expected.length && mean < 1e-3 && max < 2e-2 && dec === 'ሰላም ሰ';
console.log('ctc decode:', dec, '\n', ok ? 'PASS' : 'FAIL'); process.exit(ok ? 0 : 1);
