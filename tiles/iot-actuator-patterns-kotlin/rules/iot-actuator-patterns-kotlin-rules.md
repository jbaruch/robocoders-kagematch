# IoT Actuator Patterns — Kotlin Rules

Three patterns for driving rate-limited IoT actuators from real-time producers in Kotlin/coroutines.

## Debounce controller (→ `debounce-controller-kotlin` skill)

- **One controller coroutine per device.** Never call the IoT API from the producer loop.
- **Min-interval depends on transport:**
  - LAN devices (Shelly, local HTTP): `0.2 s`
  - Cloud APIs (Govee, Hue Bridge cloud, Tuya): **`1.2 s`** — measured ceiling on Govee is ~7 req/min sustained.
- **Stability filter:** target must hold for **2 consecutive ticks** before commit. Smooths noisy producers.
- **Send-latest:** overwrite the pending target during throttle; never queue stale intents.
- **Tick `0.4 s`.**
- **Dispatch on `Dispatchers.IO`, NOT `Dispatchers.Default`.** `Default` is the CPU-bound dispatcher; IO operations starve other CPU work there.

## Target quantization (→ `target-quantization-kotlin` skill)

- **Discrete targets only.** Float targets from noisy producers never satisfy the 2-tick stability filter.
- **Quantise to the device's distinguishable output resolution.** 6 LED segments → `Int 0..6`. 3-level semaphore → `Int 0..2`.
- Use Kotlin `Int` types for the target. Avoid passing `Float`/`Double` through `submit()`.
- Log `submit()` calls for 5 s and count distinct values. If >> the visible-state count, quantise harder.

## Progress bar rendering (→ `render-progress-bar-kotlin` skill)

- **Fill bottom-up.** Thermometer, not falling bar.
- **Red → Yellow → Green** gradient.
- For top-indexed hardware (segment 0 is physically at the top): lit range = `(total - lit) until total`.
- For bottom-indexed hardware: `0 until lit`.
- **Never** `segments.forEachIndexed { i, _ -> if (i < lit) light(i) }` — that's top-down fill.

## Idiomatic stack

- HTTP: **Ktor client + CIO engine** (`io.ktor:ktor-client-cio`). Coroutine-friendly out of the box.
- CLI: **Clikt** (`com.github.ajalt.clikt:clikt`) for argument parsing.
- Logging: **Logback + SLF4J** (`ch.qos.logback:logback-classic`). **Structured logging is mandatory** for visibility into state transitions and API failures.
- Concurrency: `kotlinx.coroutines` with `Dispatchers.IO` for the controller, `Dispatchers.Default` only for CPU-bound transforms.

## Anti-patterns

- ❌ `runBlocking { client.post(...) }` inside the producer loop — blocks the producer at network latency.
- ❌ `delay(200)` between API calls regardless of transport — 200 ms is wrong for cloud APIs.
- ❌ `Dispatchers.Default` for HTTP work — IO-bound calls belong on `Dispatchers.IO`.
- ❌ Float targets through the stability filter — `0.0001` noise blocks every commit.
- ❌ `for (i in segments.indices) { if (i < lit) light(i) }` — top-down fill, looks like a falling bar.

Reference scripts: `scripts/DebounceController.kt`, `scripts/ProgressBar.kt`.
Full context in `skills/*/SKILL.md`.
