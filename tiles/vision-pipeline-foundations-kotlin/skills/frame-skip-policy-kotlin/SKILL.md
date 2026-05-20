---
name: frame-skip-policy-kotlin
description: Run expensive per-frame inference (face recognition, emotion classification, ViT) at a fraction of the capture rate so the producer loop stays at 30 fps. Includes the 4× downscale pattern for Haar face detection, persisted-overlay technique for skipped frames, and Flow.sample() vs manual modulo approaches. Use when designing a vision pipeline that combines high-rate capture (30 fps+) with heavy per-frame work and you don't need every frame to be inferred.
---

# Frame-Skip Policy (Kotlin)

A 30 fps capture loop running face recognition + emotion classification on every frame burns 3× the CPU for zero visible improvement. The pattern: capture at full rate, **infer at a fraction**, persist the last result across skipped frames so the preview stays smooth.

## The recipe

| Stage | Recommended skip | Effective rate | Reason |
|---|---|---|---|
| Camera grab | every frame | 30 Hz | Smooth preview |
| Face detection (Haar) | every 3rd frame | 10 Hz | Faster than perception; doubles as flicker suppression |
| Face recognition (DJL face_feature) | every 3rd frame | 10 Hz | Couple to detection |
| Emotion classification (ViT/FER+) | every 30th frame | 1 Hz | Emotions change on seconds, not frames |
| IoT actuator commit | gated by debounce controller | up to ~0.83 Hz | See `iot-actuator-patterns-kotlin` |

## Persisted-overlay pattern

The preview encoder runs on **every** frame so playback looks smooth. The detection results from the last inference frame are kept in a `var` and re-drawn on skipped frames:

```kotlin
var lastIdentities: List<Triple<Rect, String, Float>> = emptyList()

while (true) {
    val frame = grabber.grab() ?: continue
    frames++

    if (frames % 3 == 0) {  // detection cadence
        lastIdentities = detectAndRecognize(frame)
    }
    // Always re-draw the latest known boxes on the current frame
    drawBoxes(frame, lastIdentities)
    preview.update(encodeJpeg(frame))
}
```

The face boxes lag 2 frames behind reality (~66 ms at 30 fps). Imperceptible.

## The 4× downscale (critical for Haar)

Haar cascades produce **far more false-positive faces at full 1280×720 than at 320×180**. Downscaling before detection is both faster (4×) and cleaner (fewer false positives):

```kotlin
val small = Mat()
resize(color, small, Size(color.cols() / 4, color.rows() / 4))
val gray = Mat()
cvtColor(small, gray, COLOR_BGR2GRAY)
cascade.detectMultiScale(gray, faces, 1.2, 5, 0, Size(20, 20), Size(0, 0))

// Scale bounding boxes back up to full-resolution coordinates
for (i in 0 until faces.size()) {
    val s = faces.get(i)
    val r = Rect(s.x() * 4, s.y() * 4, s.width() * 4, s.height() * 4)
    // ... use r for crop + recognition on the full-resolution frame
}
```

**Crop for recognition at full resolution** — embeddings benefit from the extra pixels even though detection ran on the downscaled frame.

## Modulo-based skip (simple)

```kotlin
var frames = 0
while (true) {
    frames++
    val frame = grabber.grab() ?: continue
    val doDetect = frames % 3 == 0
    val doEmotion = frames % 30 == 0
    // …
}
```

Easy to reason about. The numbers are knobs: tighten/loosen per stage.

## Flow.sample (idiomatic for Flow-based pipelines)

```kotlin
val frameFlow: Flow<Mat> = flow {
    while (currentCoroutineContext().isActive) {
        grabber.grab()?.let { emit(matConverter.convert(it)) }
    }
}

val detections: Flow<List<FaceResult>> = frameFlow
    .sample(100.milliseconds)            // ≈10 Hz, matches "every 3rd frame"
    .map { detectAndRecognize(it) }

val emotions: Flow<Emotion> = frameFlow
    .sample(1.seconds)                   // ≈1 Hz
    .map { classifyEmotion(it) }
```

`sample()` drops intermediate emissions; equivalent to modulo skip for non-integer periods.

## Watch out: detection cadence ≠ producer cadence

The producer loop should still run at full 30 fps (for preview smoothness and to keep grabber buffers from filling). Only the **inference call** is gated. Don't `delay(100)` between grabs — that breaks preview.

## Anti-patterns

- ❌ Running face detection on every frame at full resolution — 3× CPU for no perceptible improvement.
- ❌ Running emotion classification at 30 Hz — wastes inference cycles on a signal that changes once per second.
- ❌ Skipping the preview frame on inference-skip frames — preview becomes choppy 10 fps instead of smooth 30 fps.
- ❌ Downscaling AFTER face detection — the detection cost is what you needed to save.
- ❌ Cropping recognition input from the downscaled frame — embeddings lose accuracy at 320×180 resolution.

## Diagnostic: confirm skip cadence

```kotlin
var lastDetect = 0L
fun onDetect() {
    val now = System.currentTimeMillis()
    logger.debug("detect Hz={}", 1000.0 / (now - lastDetect))
    lastDetect = now
}
```

If you target 10 Hz and see 28 Hz in the log, you forgot the modulo. If you target 10 Hz and see 4 Hz, something downstream is blocking the loop (see `iot-actuator-patterns-kotlin` for the off-thread fix).
