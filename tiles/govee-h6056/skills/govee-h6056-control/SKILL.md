---
name: govee-h6056-control
description: Controls Govee H6056 Flow Plus light bars (smart LED lights) via cloud REST API with correct segment-to-bar mapping (Yankee=0-5, Golf=6-11), phantom-segment awareness (12-14 return 200 OK but do nothing), correct "off" semantics (rgb=(1,1,1), not (0,0,0)), and rate-limit guidance (~7 req/min sustained → pair with iot-actuator-patterns-kotlin debounce). Use when the user wants to control Govee H6056 light bars, change LED light colors or brightness, set bar segment colors, or automate Govee smart lighting scenes.
---

# Govee H6056 Control

Use this skill whenever you need to light up Govee Flow Plus light bars (H6056).

## Device facts the cloud docs don't tell you

- **Product form:** 2 physical bars that register as ONE API device.
- **API claims 15 segments** (`segmentedColorRgb`, min=1, max=15).
- **Physical truth: 12 segments.** Indices `12`, `13`, `14` are **phantom**. The API returns `200 OK` when you address them; no light turns on. If you don't slice your ranges correctly, your "all on" commands will look broken on stage.
- **Bar mapping** (top → bottom on each bar):
  - `bar_a` (call it Yankee): segments `0, 1, 2, 3, 4, 5`
  - `bar_b` (Golf):           segments `6, 7, 8, 9, 10, 11`
  - Within each bar, **`segment[0]` is at the TOP**. Bottom-up fill on Yankee = lighting segments 5 → 0.
- **Discovery is NOT mDNS.** Call `GET /router/api/v1/user/devices` with your API key to enumerate SKUs and device IDs.

## API contract

- **Base URL:** `https://openapi.api.govee.com`
- **Auth:** header `Govee-API-Key: <key>` (NOT bearer, NOT query param)
- **Control endpoint:** `POST /router/api/v1/device/control`
- **Capability for per-segment color:**
  - `type = "devices.capabilities.segment_color_setting"`
  - `instance = "segmentedColorRgb"`
  - `value = {"segment": [<indices>], "rgb": <packed_int>}` where `rgb = (r shl 16) or (g shl 8) or b`
- **Rate limits:** nominal ~10k/day. Sustained traffic above ~7 req/min trips 429s silently — Govee often returns 200 but the device doesn't update. Pair every Govee call with `iot-actuator-patterns-kotlin`'s debounce controller (min-interval 1.2 s).

## "Off" semantics

`rgb=(0,0,0)` packs to `0x000000`. Some firmware paths treat that as a no-op and **retain the prior segment state**. To reliably clear a segment, send `rgb=(1,1,1)` — near-black but non-zero. The user can't perceive the 1/255 brightness.

On session shutdown, always issue an explicit all-segments command:

```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    runBlocking { client.setSegments((0..11).toList(), Triple(1, 1, 1)) }
})
```

## Minimal Kotlin example

```kotlin
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import java.util.UUID

class GoveeClient(
    private val apiKey: String,
    private val sku: String,        // "H6056"
    private val device: String      // from GET /router/api/v1/user/devices
) {
    private val client = HttpClient(CIO)
    private val base = "https://openapi.api.govee.com"

    suspend fun setSegments(segments: List<Int>, rgb: Triple<Int, Int, Int>): Int {
        val (r, g, b) = rgb
        val packed = (r shl 16) or (g shl 8) or b
        val segArr = segments.joinToString(",", "[", "]")
        val payload = """
            {"requestId":"${UUID.randomUUID()}",
             "payload":{"sku":"$sku","device":"$device",
              "capability":{"type":"devices.capabilities.segment_color_setting",
                            "instance":"segmentedColorRgb",
                            "value":{"segment":$segArr,"rgb":$packed}}}}
        """.trimIndent()
        return client.post("$base/router/api/v1/device/control") {
            header("Content-Type", "application/json")
            header("Govee-API-Key", apiKey)
            setBody(payload)
        }.status.value
    }

    companion object {
        // Use these constants, NOT raw indices, to stay phantom-safe and bar-aware
        val YANKEE = (0..5).toList()
        val GOLF = (6..11).toList()
        val ALL_PHYSICAL = (0..11).toList()
    }
}

fun main() = runBlocking {
    val client = GoveeClient(
        apiKey = System.getenv("GOVEE_API_KEY"),
        sku = "H6056",
        device = System.getenv("GOVEE_H6056_DEVICE")
    )
    // Light Yankee green, Golf red:
    client.setSegments(GoveeClient.YANKEE, Triple(0, 200, 0))
    client.setSegments(GoveeClient.GOLF, Triple(200, 0, 0))
}
```

## Bottom-up fill on Yankee

Because `segment[0]` is the TOP, a bottom-up "thermometer" fill means lighting from the high-index end:

```kotlin
fun yankeeBottomUp(lit: Int): IntRange = (6 - lit) until 6   // total=6

// lit=1 → segment 5 (just the bottom)
// lit=3 → segments 3, 4, 5
// lit=6 → segments 0..5 (full bar)
```

See `render-progress-bar-kotlin` for the full gradient pattern.

## Discovery

```kotlin
suspend fun discoverDevices(client: HttpClient, apiKey: String): List<Map<String, Any?>> {
    val resp = client.get("https://openapi.api.govee.com/router/api/v1/user/devices") {
        header("Govee-API-Key", apiKey)
    }
    // resp.bodyAsText() → {"code":200, "data":[{sku, device, deviceName, ...}], ...}
    // Parse with kotlinx.serialization or Jackson; extract entries where sku == "H6056"
    TODO("parse JSON, return entries")
}
```

## Anti-patterns

- ❌ Hardcoding `15` as the segment count — sends 3 wasted commands to phantom segments.
- ❌ Splitting "thirds" as `0..4, 5..9, 10..14` for a semaphore — middle band spans Yankee + Golf, top band addresses phantoms.
- ❌ `rgb=(0,0,0)` for "off" — silently no-ops on some firmware paths.
- ❌ Calling the API inline from a 30 fps producer loop without debounce — Govee throttles silently, your producer blocks on network latency, demo framerate drops to 3 fps.
- ❌ mDNS discovery — Govee H6056 doesn't broadcast mDNS. Use the cloud `/devices` endpoint.

## Required environment

```bash
export GOVEE_API_KEY=<your-developer-api-key>   # from developer.govee.com
export GOVEE_H6056_SKU=H6056
export GOVEE_H6056_DEVICE=<the device-id field from /devices response>
```
