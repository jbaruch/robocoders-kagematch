---
name: debounce-controller-kotlin
description: One-coroutine-per-device debounce controller for rate-limited IoT APIs in Kotlin. Min-interval throttle, 2-tick stability filter, send-latest semantics. Min-interval is 0.2s for LAN devices, 1.2s for cloud APIs. Dispatches on Dispatchers.IO. Use when a real-time producer (camera loop, sensor feed, Flow<T>) drives a cloud or LAN IoT device that can't keep up with per-frame updates, or when you see flicker / HTTP 429 errors from hammering an actuator.
---

# Debounce Controller (Kotlin)

When a real-time producer (camera loop at 30 fps, sensor `Flow<T>`, event stream) drives a rate-limited IoT actuator, **the producer must not call the API directly**. Block the producer on network latency once and the whole pipeline stutters.

## The pattern

One coroutine per device. Producer calls `submit(target)`, returns immediately. Controller ticks every 0.4 s, applies the latest stable target subject to min-interval throttle.

```kotlin
class DebounceController<T>(
    private val name: String,
    private val minIntervalMs: Long,   // 200 for LAN, 1200 for cloud
    private val tickMs: Long = 400,
    private val stabilityTicks: Int = 2,
    private val onApply: suspend (T) -> Unit
) {
    @Volatile var target: T? = null
        private set
    @Volatile var committed: T? = null
        private set
    private var stable = 0
    private var lastApply = 0L

    fun submit(t: T) { target = t }

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(tickMs)
            val t = target ?: continue
            if (t == committed) { stable = 0; continue }
            stable++
            if (stable < stabilityTicks) continue
            val now = System.currentTimeMillis()
            if (now - lastApply < minIntervalMs) continue
            try {
                onApply(t)
                committed = t
                stable = 0
                lastApply = now
                logger.info("[{}] applied target={}", name, t)
            } catch (e: Exception) {
                logger.warn("[{}] apply failed: {}", name, e.message)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DebounceController::class.java)
    }
}
```

## Min-interval cheat sheet

| Transport | Example device | min-interval |
|---|---|---|
| LAN HTTP | Shelly bulb, Hue Bridge LAN, ESP32 | **0.2 s** |
| Cloud REST | Govee, Tuya, Smartthings | **1.2 s** (Govee tops out at ~7 req/min sustained) |
| Cloud + WS | LIFX cloud, Nanoleaf cloud | 1.0 s |
| BLE | Govee BLE-only models | 0.5 s (radio backoff) |

When in doubt, measure: hammer the API at 10 req/s for 30 s, count 200 vs 429.

## Stability filter

Why 2 consecutive ticks? A noisy producer (face recognition flickering between detected/not-detected) sends `submit(2), submit(0), submit(2), submit(0)` rapidly. Without the filter, the controller commits both. With the filter, it commits only when the producer settles for 0.8 s (2 × 0.4 s tick).

Higher `stabilityTicks` = smoother but laggier. 2 is usually right. Bump to 3 only for very noisy producers.

## Send-latest semantics

Never queue intents. If `submit(1)` arrives during throttle and `submit(2)` arrives a tick later, the controller commits `2`, not `1` then `2`. Stale intents are wasted API calls and confuse the user.

## Wiring it up

```kotlin
val httpClient = HttpClient(CIO)
val yankee = DebounceController<Int>(
    name = "yankee",
    minIntervalMs = 1200, // Govee cloud
    onApply = { level -> applyYankeeLevel(httpClient, level) }
)
val golf = DebounceController<String>(
    name = "golf",
    minIntervalMs = 1200,
    onApply = { emotion -> applyGolfEmotion(httpClient, emotion) }
)
runBlocking {
    yankee.start(this)
    golf.start(this)
    frameFlow.collect { frame ->
        val conf = recognize(frame)
        yankee.submit(quantize(conf))           // returns immediately
        golf.submit(detectEmotion(frame))       // returns immediately
    }
}
```

## Anti-patterns

- ❌ Calling `onApply` inline from the producer loop — that's the bug this pattern fixes.
- ❌ `Dispatchers.Default` for the controller — that's CPU-bound; IO HTTP calls belong on `Dispatchers.IO`.
- ❌ Single controller for multiple devices — each device needs its own min-interval clock.
- ❌ `Channel<T>(UNLIMITED)` of intents — that's a queue, defeats send-latest.
- ❌ `flow.debounce(1200)` alone — `Flow.debounce` waits for a quiet period; the controller pattern still applies on each emission. Combine if needed but they're not equivalent.

## Visibility

**Mandatory structured logging.** Without it, you cannot diagnose why `submit()` is called but `_apply()` never fires.

```kotlin
logger.info("[{}] submit target={}", name, t)        // every submit (DEBUG in prod)
logger.info("[{}] applied target={}", name, t)       // on commit
logger.warn("[{}] throttled (lastApply={}ms ago)", name, sinceLast)  // on throttle
```

Log distinct values seen by `submit()` for the first 5 s of operation. If you see >100 distinct values, your producer is noisy and `target-quantization-kotlin` is your next stop.
