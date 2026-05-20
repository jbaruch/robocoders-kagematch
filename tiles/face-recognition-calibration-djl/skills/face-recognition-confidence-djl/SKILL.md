---
name: face-recognition-confidence-djl
description: Compute perceptually-correct confidence from DJL face_feature cosine distances using piecewise mapping (d ≤ 0.30 → 1.0, d ≥ 0.65 → 0.0, linear between). Includes enrollment averaging, L2 normalization, and the "textbook formula compresses strong matches" anti-pattern. Use when mapping FaceNet/ArcFace cosine distance to a user-facing confidence score, driving a confidence display (semaphore, progress bar, gauge), or diagnosing why a strong-looking recognition still reads as "yellow" or "weak" downstream.
---

# Face Recognition Confidence — DJL face_feature

For DJL `face_feature` (ArcFace-derived, 512-d, L2-normalized, **cosine distance**), the textbook similarity formula does the wrong thing visually. This skill encodes the calibration we measured on real hardware.

## The textbook formula and why it fails

```kotlin
// Textbook: from face_recognition tutorials and most blog posts
val conf = max(0f, 1f - dist / TOL)  // TOL = 0.6
```

With our measured baruch-in-frame distance of `0.20–0.30`:
- d = 0.20 → conf = 0.67 → just barely "green" in a 3-band semaphore
- d = 0.30 → conf = 0.50 → "yellow" (middle band)
- d = 0.40 → conf = 0.33 → "yellow"
- d = 0.55 → conf = 0.08 → "red"

The user is **clearly recognized** at d=0.30 but the bar shows yellow. They lean in to "improve the signal" and the bar... goes to green at d=0.18. The textbook formula compresses strong matches into the middle band.

## The piecewise formula that works

```kotlin
fun confidenceOf(d: Float): Float = when {
    d <= 0.30f -> 1.0f
    d >= 0.65f -> 0.0f
    else -> (0.65f - d) / 0.35f
}
```

With the same distances:
- d = 0.20 → conf = 1.0 → **green** (strong match)
- d = 0.30 → conf = 1.0 → **green**
- d = 0.40 → conf = 0.71 → **green** (still strong)
- d = 0.55 → conf = 0.29 → **red** (genuinely weak)
- d = 0.65 → conf = 0.0 → **red** (reject)

## The full pipeline (Kotlin/DJL)

```kotlin
import ai.djl.modality.cv.Image
import ai.djl.modality.cv.ImageFactory
import ai.djl.ndarray.NDList
import ai.djl.ndarray.types.DataType
import ai.djl.repository.zoo.Criteria
import ai.djl.translate.Batchifier
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext
import kotlin.math.sqrt

class FaceFeatureTranslator : Translator<Image, FloatArray> {
    override fun getBatchifier(): Batchifier = Batchifier.STACK

    override fun processInput(ctx: TranslatorContext, input: Image): NDList {
        var array = input.toNDArray(ctx.ndManager, Image.Flag.COLOR)
        array = array.transpose(2, 0, 1).toType(DataType.FLOAT32, false)
        array = array.sub(127.5f).mul(0.0078125f) // (x - 127.5) / 128
        return NDList(array)
    }

    override fun processOutput(ctx: TranslatorContext, list: NDList): FloatArray {
        val raw = list.singletonOrThrow().toFloatArray()
        // L2 normalize so cosine distance = 1 - dot(a, b)
        val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-8f)
        return FloatArray(raw.size) { raw[it] / norm }
    }
}

fun loadFaceFeatureModel() = Criteria.builder()
    .setTypes(Image::class.java, FloatArray::class.java)
    .optModelUrls("https://resources.djl.ai/test-models/pytorch/face_feature.zip")
    .optModelName("face_feature")
    .optTranslator(FaceFeatureTranslator())
    .optEngine("PyTorch")
    .build()
    .loadModel()

fun cosineDistance(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return 1f - dot  // both L2-normalized
}

fun confidenceOf(d: Float): Float = when {
    d <= 0.30f -> 1.0f
    d >= 0.65f -> 0.0f
    else -> (0.65f - d) / 0.35f
}
```

## Enrollment averaging

For each enrolled person, embed N reference photos and average:

```kotlin
val embeddings: List<FloatArray> = photos.map { predictor.predict(it) }
val avg = FloatArray(embeddings[0].size)
for (e in embeddings) for (i in e.indices) avg[i] += e[i]
for (i in avg.indices) avg[i] /= embeddings.size.toFloat()
// Re-normalize the average (otherwise cosineDistance is meaningless)
val norm = sqrt(avg.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-8f)
for (i in avg.indices) avg[i] /= norm
```

## Picking a threshold

For "known vs unknown" decision (Stage 2 identity color), the threshold is **0.60** for our measured distances:

```kotlin
val (who, dist) = enrolled
    .map { (name, ref) -> name to cosineDistance(emb, ref) }
    .minBy { it.second }
val label = if (dist > 0.60f) "unknown" else who
```

For a confidence MEASURE (Stage 3 semaphore), use the piecewise `confidenceOf(d)`. They serve different purposes:
- Threshold → discrete "is this person enrolled?"
- Confidence → continuous "how sure am I right now?"

## Anti-patterns

- ❌ `conf = 1 - d / TOL` (textbook) — compresses strong matches.
- ❌ Forgetting to L2-normalize the averaged enrollment embedding — cosineDistance returns nonsense.
- ❌ Reusing the dlib `face-recognition-calibration` constants (`d ≤ 0.30 → 1.0, d ≥ 0.60 → 0.0`) verbatim — the upper bound is wrong for DJL face_feature (use 0.65 instead).
- ❌ Calibrating once on phone photos and trusting it for webcam runtime — the band shifts.
- ❌ Mixing color orders (RGB enrollment, BGR runtime, or vice versa) — silently produces 0.7+ "unknown" for everyone.

## Diagnostic: print distances when troubleshooting

If recognition is unreliable, print the distance to every enrolled person for 5 s:

```kotlin
val dists = enrolled.map { (name, ref) -> name to cosineDistance(emb, ref) }
logger.info("dists: {}", dists.joinToString { "${it.first}=${"%.3f".format(it.second)}" })
```

Look for:
- True identity has distance < 0.45 ✓
- Other enrolled people have distance > 0.55 ✓
- Spread between true and others > 0.15 ✓

If "true identity = 0.55, others = 0.58" → enrollment is too loose. Re-enroll with tighter face crops.
If "true identity = 0.20, others = 0.25" → enrollment is too tight (probably you enrolled the same photo multiple times).
