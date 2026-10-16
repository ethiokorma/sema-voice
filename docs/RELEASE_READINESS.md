# RELEASE_READINESS (2026-10-15)

## Decision: **Not ready for public beta. Ready for internal device testing.**

## Measured progress

| Gate (from the plan) | Target | Measured | Met? |
|---|---|---|---|
| Historical baseline | 0.81 | shook-tiny 0.812 raw | n/a |
| First improvement | WER < 0.50 | Ethio-ASR int8 ONNX **0.235** (fp32 0.219) | Yes |
| Experimental beta | WER < 0.30 | 0.235 | Yes, on 30 read-speech clips only |
| Strong beta | WER < 0.20 | 0.219 fp32 / 0.235 int8 | No |
| Long-term | WER ≤ 0.10 | — | No |
| Hallucination or repetition | low | CTC: 0 / 30. Whisper fallback: 7 / 30 trimmed and flagged by the guard. | Yes |
| Correct Amharic Unicode | yes | 30 / 30 Ethiopic for the CTC models | Yes |
| Device testing | done | **not done**: no Android device was connected | **No** |
| Keyboard insertion on device | done | not done | **No** |

## Blockers

1. **No phone measurement.** The 876 MB int8 model needs about 1.8 GB RAM, and its speed on
   ARM is unknown (RTF 1.25 on a 4-core 2016 Xeon). Severity: **high.** It could be too slow on
   mid-range phones, which would leave the Whisper fallback as the only option there.
2. **Model size.** 876 MB, because ORT has no ConvInteger kernel and only MatMuls are int8.
   Options: static int8 with QDQ for the convs (XNNPACK), fp16, or the 315M XLS-R model with
   word re-joining. Severity: **medium.**
3. **Benchmark breadth.** 30 read-speech FLEURS clips, one domain, 272 words. Still needed:
   conversational speech, noise, names and numbers, mixed Amharic-English, and multiple
   speakers. Severity: **medium.**
4. **WAXAL ShareAlike question** for the shipped model (see LICENSES). Severity: **medium**,
   legal review needed.
5. **The PWA phone crash** (from the previous session) has not been re-tested. Severity: medium.
6. **Cloud providers** are designed but not built (needs publisher accounts and a proxy).
   Severity: low, since cloud is optional.

## What a beta tester gets today (once installed)

* Amharic dictation with the CTC model when the phone has the RAM, and the Whisper tiny fallback
  otherwise.
* The repetition guard on the fallback. Flagged output is never auto-inserted.
* English on stock Whisper.
* Offline translation (OPUS-MT / M2M100) as before. Its Amharic quality has not been re-measured
  this session.
