---
name: target-quantization-kotlin
description: Discretise continuous producer signals (Float, Double) into Int targets so the debounce controller's stability filter can actually commit. Without quantization, a noisy 0.42-vs-0.43-vs-0.42 signal blocks every commit and the actuator stays dark. Use when wiring a continuous producer (confidence score, sensor reading, audio level) into a debounce controller, or debugging "I call submit() but onApply() never fires".
---

# Target Quantization (Kotlin)

The debounce controller's stability filter only commits when the target holds for 2 consecutive ticks. Float targets from a noisy producer **never** hold — they wobble by `0.0001` every frame. The controller blocks forever and your actuator stays dark.

## The fix

Quantise the producer's float to a small `Int` matching the device's output resolution **before** calling `submit()`.

```kotlin
// 6-segment LED bar — 7 visible states (0..6)
fun quantizeForBar(continuous: Float): Int =
    (continuous * 6).toInt().coerceIn(0, 6)

// 3-level semaphore — 3 visible states (0, 1, 2)
fun quantizeForSemaphore(continuous: Float): Int = when {
    continuous < 0.33f -> 0
    continuous < 0.67f -> 1
    else -> 2
}

// HSV bulb — 360 hues feel like ~36 distinct colors to humans
fun quantizeHue(degrees: Float): Int =
    ((degrees / 10).toInt() * 10).coerceIn(0, 350)
```

## Rule of thumb

**Quantise to the device's distinguishable output resolution, not the producer's input resolution.**

The producer might generate 32-bit float confidence. The device shows 6 LED segments. Quantising to `Int 0..6` means the controller sees 7 distinct values total over a session. Stability filter commits cleanly.

## Diagnostic: count distinct values

If you're not sure your quantization is aggressive enough, log distinct `submit()` values for 5 s:

```kotlin
val seen = ConcurrentHashMap.newKeySet<Int>()
fun submitInstrumented(t: Int) {
    seen.add(t)
    controller.submit(t)
}
// after 5 s:
logger.info("[quant] distinct values in 5s: {} {}", seen.size, seen)
```

**Threshold:** `seen.size` should be `≤ device.visibleStates`. If it's 50x that, the producer is noisy and you need finer quantization or a smoothing filter upstream.

## Common producer → device mappings

| Producer signal | Device | Recommended quantization |
|---|---|---|
| Face-recognition confidence (cosine dist) | 3-level RAG semaphore | `when { c<0.33 -> 0; c<0.67 -> 1; else -> 2 }` |
| Audio RMS (dB) | 6-segment volume meter | `((dbNormalized * 6).toInt()).coerceIn(0, 6)` |
| Temperature (°C, 0..40) | Hue bulb temperature 2700..6500 K | nearest 200 K |
| Battery % | 4-bar indicator | `(pct / 25).toInt().coerceIn(0, 4)` |

## Anti-patterns

- ❌ Passing `Float`/`Double` directly to `controller.submit()` — equality check in stability filter never holds.
- ❌ Quantising to too many levels (e.g., `Int 0..100` for a 6-segment bar) — wasted granularity, still noisy.
- ❌ Quantising before smoothing — applies bins to noise. Smooth (moving avg, EMA) first, then quantise.
- ❌ Different quantization per submit — `submit(a)` with one binning, `submit(b)` with another. Pick one mapping and stick with it.

## When quantization isn't enough

If your producer is so noisy that even Int 0..2 wobbles every tick, smooth upstream:

```kotlin
val smoothed: Flow<Float> = rawConfidence
    .runningReduce { acc, v -> 0.7f * acc + 0.3f * v }  // EMA
    .map { quantizeForSemaphore(it) }
    .distinctUntilChanged()
```

Combine smoothing (kills noise) + quantization (matches device) + debounce controller (rate-limits). All three play different roles. Don't conflate them.
