# face-recognition-calibration-djl

A [Tessl](https://tessl.io) plugin encoding empirical calibration for DJL `face_feature` embeddings (ArcFace-derived, 512-d L2-normalized, cosine distance) — the Kotlin/JVM equivalent of [jbaruch/face-recognition-calibration](https://github.com/jbaruch/face-recognition-calibration), which targets dlib's Python `face_recognition` library instead.

## What this plugin provides

| Kind | Name | Purpose |
|---|---|---|
| Skill | `face-recognition-confidence-djl` | Piecewise confidence mapping for DJL cosine distances. **`d ≤ 0.30 → 1.0`, `d ≥ 0.65 → 0.0`, linear between.** Includes the full FaceFeatureTranslator + L2-normalized enrollment averaging + threshold guidance. |
| Rule  | `face-recognition-calibration-djl-rules` | Distance band cheat sheet, color order, Haar parameter relaxation for enrollment, install traps. |

## Why it exists

The textbook similarity formula `conf = 1 - d / tolerance` (where `tolerance = 0.6`) is **technically correct and demo-wrong** for DJL `face_feature` distances. A strong recognition at `d ≈ 0.30` returns `conf = 0.50` — yellow in any 3-band semaphore. The user leans into the camera to "improve the signal" and confidence climbs from 0.50 to 0.67. They look uncertain about the demo.

The piecewise formula in this plugin returns `conf = 1.0` at `d = 0.30` — solid green where it belongs. Calibrated against the actual distance distribution we measured on hardware: strong matches cluster at 0.18–0.40, rejects above 0.65, with a clear gap between.

## Use this plugin if

- You're using DJL `face_feature` (PyTorch engine, 512-d) for face recognition on Kotlin/JVM.
- A strong-looking recognition still reads as "weak" downstream.
- You're driving a confidence display (semaphore, progress bar, gauge) that needs strong matches to register as strong.

Use [jbaruch/face-recognition-calibration](https://github.com/jbaruch/face-recognition-calibration) instead if you're on Python with dlib's `face_recognition` library — the constants differ (`d ≥ 0.60 → 0.0` for dlib L2 vs `d ≥ 0.65 → 0.0` for DJL cosine).

## Install

```bash
tessl install jbaruch/face-recognition-calibration-djl
```

## License

MIT — see [LICENSE](LICENSE).
