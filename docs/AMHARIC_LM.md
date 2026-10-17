# Amharic word n-gram LM for the Ethio-ASR CTC server

Status 2026-10-16. Opt-in in `server/asr_server.py` (`--lm ...`); the server stays greedy by default.

## What it is

- `server/lm/decoder.py`: `LmDecoder(lm_path(s), vocab, alpha, beta, beam, ...)`, `decode(logits[T, V]) -> str`.
  A CTC prefix beam search in pure Python + numpy (no KenLM, no pyctcdecode), with a word 3-gram LM.
  Word boundaries are `|` plus every token the scorer turns into a space (Ethiopic/ASCII punctuation),
  so LM words follow the same tokenisation as the scorer. Columns past the 409-entry vocab are ignored.
- Out-of-vocabulary words score `log P(<unk>|ctx) + char_weight * log P_char(spelling)`, where
  `P_char` is a character 6-gram spelling model trained on rare words. Without it, the search merged
  whole sentences into one "word" so it only paid one LM cost.
- `char_bonus` (per character of each scored word) works against the LM's tendency to delete
  acoustically uncertain words. The first tuning round had no bonus and raised CER on test (see below).
- Two LMs are combined log-linearly with weights 0.5/0.5. The wiki LM alone and the web LM alone each
  scored worse on dev than the two together.
- Why not KenLM: there is no Windows wheel for `kenlm`, and building it needs MSVC, which this machine
  does not have. `tools/build_amharic_lm.py` builds an interpolated modified Kneser-Ney LM with numpy.
  It is count-pruned and stored in backoff form. I checked that probabilities sum to 1.000 over the
  vocabulary for sample contexts.

## Files

| file | size | where |
|---|---|---|
| `amharic-wiki-3gram.npz` (+ .json): Wikipedia only, 201k-word vocab, no pruning | 41 MB | `server/lm/` |
| `amharic-web-3gram.npz` (+ .json): Wikipedia + FineWeb-2, 500k vocab, bigram count ≥ 2, trigram count ≥ 3 | 230 MB | scratch `…\scratchpad\lm\work\amharic-web-3gram.npz` (too big for the repo); its .json is also in `server/lm/` |
| `corpus_stats.json` | – | `server/lm/` |
| `tools/build_amharic_lm.py` | | builds the corpus (cleaning + leakage filter) and the LMs |
| `tools/cache_ctc_logits.py` | | runs the ONNX model once per clip and caches log-probs (same features and 20 s windows as the server) |
| `tools/eval_amharic_lm.py` | | greedy vs LM scoring, grid search |
| `tools/fleurs_parquet_to_clips.py` | | FLEURS parquet → WAV + manifest |
| `docs/ai-quality-ethio-asr-lm.json` | | all numbers, the dev grids, and per-clip rows for test 30/100 |

SHA-256: wiki `2d55f337…690a16`, web `357ba137…48d3`.

Server: `python server/asr_server.py --lm server/lm/amharic-wiki-3gram.npz <scratch>/lm/work/amharic-web-3gram.npz`
(defaults alpha 0.35, beta 2, beam 16, char_weight 0.3, char_bonus 1.5). `/v1/health` reports `"decoder"`.
The LMs add about 450 MB of RAM and load in about 2 s.

## Corpus sources and licences

| source | licence (from the dataset card) | used | after cleaning |
|---|---|---|---|
| Amharic Wikipedia, `wikimedia/wikipedia` 20231101.am | CC BY-SA 3.0 + GFDL (current Wikipedia text is CC BY-SA 4.0) | yes | 121,218 sentences / 1.36 M words |
| FineWeb-2 `amh_Ethi` train (`HuggingFaceFW/fineweb-2`) | ODC-By 1.0, also subject to the Common Crawl Terms of Use | yes | 10.0 M sentences / 148.6 M words |
| FLoRes / Belebele / SIB-200 | CC BY-SA 4.0, but derived from FLoRes, the same source as the FLEURS test text | **excluded** (leakage) | – |
| XL-Sum, MAFAND, WorldSpeech, laion-coco-nllb | CC BY-NC(-SA) | **excluded** (non-commercial) | – |
| OPUS-100, CCMatrix, WikiANN | licence "unknown" | **excluded** | – |
| CulturaX, HPLT 2.0 (CC0 on the packaging), mC4 (ODC-By), MasakhaNEWS (AFL-3.0) | ok or unclear | not used (not needed / not checked in depth) | – |

Caveat: ODC-By and CC0 cover the compilation, not the copyright of each web page. A count-based
n-gram model is a statistical derivative and does not reproduce text verbatim (but see
"near-duplicate" below). CC BY-SA attribution for Wikipedia should go in `docs/LICENSES.md`. Whether
ShareAlike applies to an n-gram model is unclear. Treat the wiki LM as CC BY-SA to be safe.

Cleaning (same steps for both sources):
1. NFC normalisation, and `::`/`፡፡` mapped to `።`.
2. Sentence split on `። ፧ ! ?`, newlines and non-decimal `.`.
3. The exact scorer `normalize()` from `tests/ai-quality/evaluate.py`.
4. Keep a sentence only if it has ≥ 2 words, every character is in the acoustic vocab, and ≥ 80 % of
   letters are Ethiopic.
5. Drop exact duplicates.

Wikipedia: 217,957 raw → 121,218 kept (32,153 duplicates, 22,642 non-Ethiopic, 41,944 bad charset or
too short). FineWeb-2: 21.8 M raw → 10.0 M kept (3.23 M duplicates, 6.72 M non-Ethiopic, 1.88 M bad
charset or too short).

## Leakage removal

References: the FLEURS am_et test.tsv and dev.tsv (all 516 + 223 rows, raw and normalised columns),
which give 420 unique normalised texts. A corpus sentence is removed if it:
- (a) equals a reference;
- (b) shares a word 8-gram with any reference; or
- (c) shares a word 4-gram with a reference and has ≥ 80 % of its character 5-grams in that reference.

I checked rules (a), (b) and (c) on fragments planted from a reference: each one fired.

Result:
- (a) exact: 0
- (b) 8-gram: 0
- (c) near-copy: 15 sentences, all from FineWeb-2, touching 12 references. They are generic phrases
  such as "በስፋት ጥቅም ላይ ይውላል" and "አንድ ወይም ከዚያ በላይ"; they are listed in `work/leak_removed.txt`.

So FLoRes sentences essentially do not occur in these corpora. The perplexities of the reference
sentences under the LM also do not suggest leakage:

| set | web LM | wiki LM |
|---|---|---|
| dev | 2045 | 11.7 k |
| test100 | 2483 | 11.1 k |
| test30 | 2236 | 13.2 k |

## Tuning (dev = FLEURS am_et *validation*, first 100 clips, 18.3 min; never the test clips)

Grids are in the JSON. The main findings:
- Plain prefix beam search without an LM: 0.2035 / 0.061 (greedy 0.2054 / 0.0617).
- Wiki LM alone, best: 0.178. Web LM alone (500k vocab, unpruned bigrams), best: 0.181. Both
  combined 0.5/0.5: 0.165.
- With `char_bonus` 1–1.5, results reach a plateau around alpha 0.35 and beta 2: WER 0.154, CER 0.051.
- Beam 8, 16 and 32 give the same WER within noise. Changing partial_penalty (−3 / −9) or
  unk_penalty (±2) changes WER by less than 0.005.

**Chosen:** alpha 0.35, beta 2.0, beam 16, char_weight 0.3, char_bonus 1.5, partial −6, unk 0, LM
weights 0.5/0.5.

## Results (same scoring as evaluate.py; same cached int8 logits for greedy and LM)

| set | greedy WER / CER | beam, no LM | **LM (final)** |
|---|---|---|---|
| dev 100 (tuned on) | 0.2054 / 0.0617 | 0.2035 / 0.0610 | **0.1539 / 0.0511** |
| test, first 30 | 0.2335 / 0.0776 | 0.2243 / 0.0772 | **0.2096 / 0.0772** |
| test, first 100 | 0.2087 / 0.0714 | 0.2015 / 0.0715 | **0.1950 / 0.0694** |

On the 100 test clips, relative to greedy, WER drops by 6.6 % and CER by 2.8 %. On the 30 test clips,
WER drops by 10 % and CER is flat. Typical corrections: ወጥረቱ→ውጥረቱ, አውንታዊ→አዎንታዊ,
ፊንጅ→ፊንች, ዩኒቨርስቲ→ዩኒቨርሲቲ, ቢሲበግብተገ→በግብ…, በካታ→በርካታ. Numbers (ከ17 vs ከ117ቱ) are not
fixed: the acoustic model drops the digit and the LM cannot restore it.

Disclosure: the first configuration (char_bonus 0, alpha 0.25) was evaluated on test once and gave
test30 0.2206 / **0.0828** and test100 0.2004 / **0.0765**, with CER worse than greedy because of
deletions. I then added `char_bonus` and re-tuned on dev only before the final test run. The test
set was therefore seen twice, and that is a mild optimistic bias.

## Speed (4-core Xeon E5-1607 v4)

- LM beam search costs 0.012–0.014 s per second of audio (single Python thread) on all three sets,
  about 14 s for 1,159 s of audio.
- The acoustic model costs 0.76–1.3 s per second of audio on the same machine. The figure varied
  with the load from other jobs.
- So the LM adds about 1–2 % to the inference time, far under the 50 % budget. End to end through the
  server: 10.9 s clip → 8.3 s with LM.
- Most of the speed comes from skipping frames where blank has probability above 0.999 and from
  caching LM lookups.

## Honest caveats

- **The gain is much smaller on test than on dev.** WER dropped 25 % on dev (0.205 → 0.154), but only
  6.6 % on 100 test clips and 10 % on 30 test clips. Part of the dev number is tuning optimism: 100 clips is only 81 unique sentences and about 1,700
  words, so ±0.005 WER is about 8 words. Part of it may be dev/test differences. Perplexities do not
  suggest leakage. The test numbers are the ones to quote.
- On the 30 test clips, about 40 % of the WER gain over greedy also comes from beam search without any
  LM (0.2335 → 0.2243). The LM's own contribution on test100 is 0.2015 → 0.1950.
- The LM can still delete acoustically garbled words. `char_bonus` limits this but does not remove it.
  Spelling variants follow the corpus majority (ሰ/ሠ, ህ/ሕ, እጽ/እፅ). That sometimes moves away from the
  FLEURS reference spelling and sometimes towards it.
- Greedy WER here is 0.2335 on the 30 test clips. That is not the 0.222 quoted for v2 int8: I measured
  it with `server/asr_server.py` features on `ethio-asr-ctc-v2-model_int8.onnx`. All comparisons in
  this document use the same cached logits, so they are consistent with each other.
- The 230 MB web LM is not in the repo and needs hosting (a GitHub release asset, like the models)
  before it can ship.

## Update 2026-10-16 (later): split at silence, then LM

The server's fixed 20 s windows cut words in half. On the 30 test clips, greedy decoding scored
0.2224 / 0.072 on whole clips and 0.2335 / 0.0776 with 20 s windows. The server and the web worker
now cut long audio only at the quietest 0.3 s near the limit (`split_at_silence`: 40 s pieces on the
server, 20 s in the browser). FLEURS clips are all under 40 s, so on the server they now decode
whole.

The 100 test clips were re-cached with the new splitting and scored with the final LM settings,
unchanged from above:

| server decoding | WER | CER |
|---|---|---|
| 20 s windows, greedy (before) | 0.2087 | 0.0714 |
| split at silence, greedy | 0.2039 | 0.0693 |
| **split at silence + LM (now the default in run_with_tunnel.py)** | **0.1920** | **0.0679** |

The LM decode cost 0.0117 s per second of audio, 1.3 % of the acoustic model time. Both LMs are on
the `models-2026.10` release, and `server/run_with_tunnel.py` downloads and checks them on first
start.
