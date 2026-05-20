# Face Recognition Calibration Rules — DJL face_feature

When working with **DJL** `face_feature` (PyTorch engine, ArcFace-derived, **512-d L2-normalized embeddings**, **cosine distance**):

## Confidence (→ `face-recognition-confidence-djl` skill)

- **Use piecewise mapping calibrated for FaceNet/ArcFace cosine distance:**
  - `d ≤ 0.30 → 1.0` (strong)
  - `d ≥ 0.65 → 0.0` (reject)
  - Linear between: `conf = (0.65 - d) / 0.35`
- **Avoid** the textbook `1 - d / tolerance` formula. With `tolerance = 0.6` and a strong match at `d ≈ 0.30`, that returns `0.50` — strong matches compress into the middle "yellow" band of any UI built on top.
- **Cosine distance is not similarity.** Lower = closer match. For L2-normalized embeddings, `d = 1 - cos(θ)`, so `d ∈ [0, 2]`.

## Distance band cheat sheet (measured on DJL face_feature with our enrollment set)

| Band | Distance | Interpretation | Bulb behavior |
|---|---|---|---|
| strong | 0.18 – 0.40 | known person, normal pose | identity color, conf = 1.0 |
| borderline | 0.40 – 0.60 | known person, off-angle / blur | identity color, conf 0.14–0.71 |
| reject | > 0.65 | unknown person (or no enrollment match) | white / "unknown" |
| garbage | > 0.85 | Haar false-positive face, not a real face | drop the detection entirely |

## Color/channel order

- DJL's default `Image.Flag.COLOR` returns **RGB** tensors. The bundled `face_feature` model was trained on this convention. **Do not swap to BGR** unless you specifically validated that. Stage 2 in this repo runs RGB → strong matches at d≈0.20. Swapping to BGR makes everything > 0.6.

## Enrollment quality (cross-references `face-recognition-enrollment`)

- Target **intra-class cosine distance mean 0.20–0.35**. `<0.15` = overfit; `>0.45` = loose cloud (likely bad photos).
- **Face coverage 60–75%** of frame height.
- **5–7 photos per person** with pose + lighting variety: front-neutral, front-smile, three-quarter-left, three-quarter-right, bright, dim.
- Use the **same camera and lighting at enrollment as at runtime** if possible. Cross-source enrollment (phone photos enrolling for webcam runtime) consistently shifts the distance band up ~0.05–0.10.

## Detection-vs-enrollment Haar parameter relaxation

The Haar cascade that works fine for runtime (`scaleFactor=1.2, minNeighbors=4, minSize=60×60`) sometimes fails on enrollment JPGs (different pose, lighting). For enrollment ONLY, use a fallback chain:

```kotlin
val attempts = listOf(
    Triple(1.2, 4, 60),    // strict (matches live runtime)
    Triple(1.1, 3, 60),
    Triple(1.05, 3, 40),
    Triple(1.05, 2, 30)    // very loose
)
```

Return the largest face from whichever attempt fires first. **Never use these loose params at runtime** — they produce false-positive faces that pollute the recognition pipeline.

## Persistence (cross-references `face-recognition-persistence`)

- Detectors miss 10–20% of frames. **Hold last distance for ~0.8 s** before declaring "no face".
- Persistence is the producer-side layer; debounce in `iot-actuator-patterns-kotlin` is actuator-side. They compose.

## Install traps

- DJL PyTorch native libs are ~hundreds of MB on first extraction. Pre-warm `~/.djl.ai/cache/` ahead of demo time. Don't trust conference Wi-Fi.
- The `face_feature` model URL is `https://resources.djl.ai/test-models/pytorch/face_feature.zip` (used in DJL examples). It downloads ~100MB on first model load.

Full skill reference: `skills/face-recognition-confidence-djl/SKILL.md`.
