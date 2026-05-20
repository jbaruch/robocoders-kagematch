# iot-actuator-patterns-kotlin

A [Tessl](https://tessl.io) plugin encoding the Kotlin/coroutines patterns for driving rate-limited IoT actuators from real-time producers — the kind of pipeline you build when a 30 fps camera feed needs to drive a cloud-throttled smart light.

## What this plugin provides

| Kind | Name | Purpose |
|---|---|---|
| Skill | `debounce-controller-kotlin` | One-coroutine-per-device debounce controller. **0.2 s min-interval for LAN, 1.2 s for cloud APIs**, 2-tick stability filter, send-latest semantics, `Dispatchers.IO`. |
| Skill | `target-quantization-kotlin` | Discretise float producer signals to `Int` so the stability filter can commit. Without this, noisy producers block every commit and the actuator stays dark. |
| Skill | `render-progress-bar-kotlin` | Bottom-up red→yellow→green segmented bar with the index-direction fork (top-indexed vs bottom-indexed hardware). |
| Rule  | `iot-actuator-patterns-kotlin-rules` | Concise in-context reminder card. |

## Why it exists

When a producer (face recognition pipeline, sensor stream, audio meter) needs to drive a rate-limited IoT actuator, three things go wrong at once:

1. The producer calls the API inline → blocked on network latency → 30 fps drops to 3.
2. Float targets from a noisy producer wobble by `0.0001` every frame → stability filter blocks every commit.
3. The fill direction matches array index order, not physical orientation → bar fills top-down on top-indexed hardware (Govee H6056).

This plugin codifies the fix for all three, plus the LAN-vs-cloud min-interval distinction agents commonly get wrong.

## Idiomatic stack baked in

- **HTTP:** Ktor client + CIO engine
- **CLI:** Clikt
- **Logging:** Logback + SLF4J — structured logging is mandatory for diagnosing why `submit()` is called but `_apply()` doesn't fire
- **Concurrency:** `kotlinx.coroutines` with `Dispatchers.IO` (not `Default` — that's CPU-bound)

## Install

```bash
tessl install jbaruch/iot-actuator-patterns-kotlin
```

## License

MIT — see [LICENSE](LICENSE).
