// Node smoke test for the PWA model path: loads web/models/whisper-tiny-amharic through
// transformers.js (same library and files the browser uses) and transcribes a 16 kHz WAV.
//   npm i @xenova/transformers@2.17.2 wavefile   (in any scratch folder)
//   node web/test/transcribe-node.mjs <clip.wav> [reference text]
import { pipeline, env } from '@xenova/transformers';
import wavefile from 'wavefile';
const { WaveFile } = wavefile;
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
env.allowLocalModels = true;
env.allowRemoteModels = false;
env.localModelPath = path.resolve(here, '..', 'models') + path.sep;

const [clip, reference] = process.argv.slice(2);
if (!clip) { console.error('usage: node transcribe-node.mjs <clip.wav> [reference]'); process.exit(2); }
const wav = new WaveFile(readFileSync(clip));
wav.toBitDepth('32f'); wav.toSampleRate(16000);
let samples = wav.getSamples();
if (Array.isArray(samples)) samples = samples[0];

const t0 = Date.now();
const asr = await pipeline('automatic-speech-recognition', 'whisper-tiny-amharic', { quantized: true });
const loadMs = Date.now() - t0;
const t1 = Date.now();
const out = await asr(new Float32Array(samples), { language: 'amharic', task: 'transcribe', chunk_length_s: 30 });
const ms = Date.now() - t1;
console.log(JSON.stringify({ clip: path.basename(clip), loadMs, inferenceMs: ms, audioMs: Math.round(samples.length / 16), text: out.text.trim(), reference: reference || null }, null, 2));
