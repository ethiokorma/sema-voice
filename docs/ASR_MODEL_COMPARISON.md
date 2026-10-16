# ASR_MODEL_COMPARISON — Amharic speech recognition

All numbers below were **measured in this repository** on the same audio unless marked
"reported". Machine-readable copies: `benchmarks/amharic-model-comparison.{json,csv}`, per-clip
rows in `docs/ai-quality-*.json`.

## Benchmark

* **Audio:** first 30 utterances of the FLEURS `am_et` **test** split (CC-BY-4.0), 16 kHz mono.
  Read speech, 6–29 s per clip, 272 reference words in total. It is small: one clip moves
  corpus WER by about 0.4 percentage points per word error.
* **References:** the FLEURS human transcripts.
* **Normalisation:** NFC, Ethiopic punctuation (፡።፣፤፥፦፧፨) replaced by spaces, other
  non-word characters removed, lower-cased.
* **Metrics:** WER = total word edit distance / total reference words, over the whole corpus.
  CER uses characters with spaces removed.
* **Raw versus guarded:** raw is the untouched model output and is the official number. Guarded
  applies `RepetitionGuard` (restart and loop trimming, see `AMHARIC_ASR_OPTIMIZATION.md`). It is
  reported alongside raw, never in place of it.
* **Hardware:** the build PC's CPU, 4 threads. Whisper runs through whisper.cpp
  (pywhispercpp). CTC models run through PyTorch fp32, greedy decoding with no language model.
  RTF is host-only, and phone numbers will differ.
* **Leakage check:** the training cards for hohe and Ethio-ASR say they evaluate on FLEURS test.
  Neither says it trained on FLEURS test. Hohe's 880 h corpus is undisclosed, so leakage cannot
  be excluded for it.

## Results (sorted by raw WER)

| Model | Arch / runtime | Size | License | WER raw | CER raw | WER guarded | Clips WER < 0.5 | Loops | RTF (host) | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| badrex/Ethio-ASR-amharic | w2v-BERT 2.0 CTC 600M, ONNX | 2.4 GB fp32 | CC-BY-4.0 (WAXAL data) | **0.219** | **0.072** | 0.219 | 26/30 | 0 | 1.58 | Best, candidate |
| snapwre/hohe-asr-amharic | w2v-BERT 2.0 CTC 600M, ONNX | 2.4 GB fp32 | CC-BY-4.0 (corpus undisclosed) | 0.221 | 0.086 | 0.221 | 27/30 | 0 | 1.11 | Best, candidate |
| b1n1yam/shook-tiny-amharic-600hr | Whisper tiny, ggml q5_1 | 31 MB | Apache-2.0 | 0.812 | 0.609 | 0.618 | 12/30 | 7/30 | 3.16 | Current fallback |
| waxal-benchmarking/whisper-tiny-waxal-amh | Whisper tiny, ggml f16 | 74 MB | Apache-2.0 | 0.772 | 0.628 | 0.732 | 6/30 | 2/30 | 4.97 | Slightly better than fallback raw, worse guarded |
| waxal-benchmarking/whisper-tiny-waxal-amh | Whisper tiny, ggml q5_1 | 31 MB | Apache-2.0 | 0.870 | 0.842 | 0.732 | 7/30 | 6/30 | 1.91 | q5_1 loses about 10 WER points versus f16 |
| agkphysics/wav2vec2-large-xlsr-53-amharic | XLS-R 315M CTC | 1.26 GB fp32 | MIT | 0.906 | **0.122** | 0.906 | 1/30 | 0 | 0.45 | Words are split ALFFA-style ("የ በረዶ"); characters mostly right. Needs a re-joining step. |
| waxal-benchmarking/whisper-small-waxal-amh | Whisper small, ggml q5_1 | 181 MB | Apache-2.0 | see `TEST_RESULTS.md` | | | | | | Measured last |
| seyyaw/whisper-finetuned-amharic | Whisper small | 465 MB | Apache-2.0 | 1.180 | 1.240 | 1.180 | 0/30 | 0 | 5.75 | Rejected (Hebrew/Latin output) |
| Stock Whisper tiny q5_1 | Whisper tiny | 31 MB | MIT | 1.287 | 1.663 | 1.287 | 0/30 | 1/30 | 0.67 | English only |

Against the release gates in the plan:

* The CTC models cross "first improvement" (below 0.50) and "experimental beta" (below 0.30).
  They are not yet "strong beta" (below 0.20). Their CER of 7–9 % means most errors are single
  characters within otherwise correct words.
* No Whisper-based model reaches 0.50 raw.

## Why the WAXAL Whisper models underperform here

The WAXAL card reports a test WER of 33.6 % for small and 41.3 % for tiny. Those figures are on
WAXAL's own spontaneous, image-prompted speech. On FLEURS read speech the tiny model drifts into
restarts and runaway text. This is a domain gap, plus Whisper's autoregressive failure mode, not
a conversion problem: the vocabulary is byte-identical to stock multilingual Whisper (51,865
tokens, `<|am|>` = 50334).

## Candidates evaluated but not run (licence or feasibility)

| Model | Reason |
|---|---|
| agkphysics/wav2vec2-large-xlsr-53-amharic (MIT, 315M CTC) | Reported 26 % WER on ALFFA read speech. The smallest clean-licence CTC (about 320 MB int8). Next to measure if 600M is too heavy for phones. |
| speechbrain/asr-wav2vec2-dvoice-amharic (Apache-2.0) | Reported 24.9 % on ALFFA. Needs a SpeechBrain-to-ONNX port. |
| b1n1yam/qwen3-asr-0.6b-amharic-mixed (Apache-2.0) | LLM decoder, so it can hallucinate. No mobile runtime. |
| Meta Omnilingual ASR CTC 300M (Apache-2.0 base) | The WAXAL Amharic fine-tune `waxal-benchmarking/omniasr-ctc-300m-waxal-amh` is **CC-BY-NC**. Base-model Amharic quality not measured yet. |
| facebook/mms-1b-all, mms-300m, waxal mms-300m | CC-BY-NC, excluded |
| badrex/Ethio-ASR-multilingual-94M / -300M | CC-BY-NC(-SA), excluded |
| misterkissi/w2v2-lg-xls-r-1b-amharic, oddegen MMS fine-tune | NC, excluded |
| BuzzASR/amharic (MIT, Whisper large-v3) | 3 GB, flagship or server only. Not measured. |
| Models with no licence stated (shook-medium-amharic-2k, Bell-whisper-large-v3-turbo-amharic, others) | Not usable until the authors add a licence |

Cloud providers are compared in `CLOUD_PRIVACY.md`.

## Licence notes for the CTC winners

* **CC-BY-4.0** permits commercial use and redistribution with attribution. The model manager
  shows the attribution, and `LICENSES.md` lists it.
* WAXAL Amharic data: Google's WaxalNLP card says **CC-BY-SA-4.0**, while waxal-benchmarking
  cards say CC-BY-4.0. Whether ShareAlike carries over to trained weights is legally unsettled.
  Flag this for review before a paid release.
* Hohe: commercially licensed weights, but the training corpus is undisclosed. Prefer
  Ethio-ASR when provenance matters, since the two are tied on accuracy.
