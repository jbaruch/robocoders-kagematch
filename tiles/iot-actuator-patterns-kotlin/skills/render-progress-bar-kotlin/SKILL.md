---
name: render-progress-bar-kotlin
description: Render a segmented LED progress bar that fills bottom-up with red/yellow/green gradient — thermometer pattern, not falling-bar. Handles top-indexed hardware (where segment[0] is physically at the top) and bottom-indexed hardware. Use when wiring a quantised level (0..N) into an LED bar, especially Govee H6056, Hue Lightstrip, or similar segmented devices where fill direction and gradient matter.
---

# Render Progress Bar (Kotlin)

A vertical LED bar that fills **bottom-up** with a **red → yellow → green** gradient is the thermometer pattern users expect. Anything else looks like a falling bar (confusing) or solid color (wasted information).

## Two rules

1. **Fill bottom-up.** Low confidence → only bottom segment lit (RED, "system on, low signal"). High confidence → full bar (RED + YELLOW + GREEN).
2. **Gradient by zone, not by segment index.**
   - Bottom third: RED (always on when any signal)
   - Middle third: YELLOW (lights when level ≥ 1)
   - Top third: GREEN (lights when level ≥ 2)

## The fork: top-indexed vs bottom-indexed hardware

This is where most agents get it wrong. **Always check the device's index direction before writing the fill code.**

### Top-indexed (segment[0] is physically at the TOP)
Govee H6056 is top-indexed. To light "bottom-up", the LIT range is the high-index end:

```kotlin
// total=6, lit=N → light segments (6-N)..(6-1)
fun litSegmentsTopIndexed(total: Int, lit: Int): IntRange =
    (total - lit) until total
```

### Bottom-indexed (segment[0] is physically at the BOTTOM)
ESP32 NeoPixel strips wired bottom-up. LIT range is the low-index end:

```kotlin
fun litSegmentsBottomIndexed(lit: Int): IntRange = 0 until lit
```

## The 3-zone semaphore (for a 6-segment top-indexed bar like Govee H6056 Yankee)

```kotlin
// Physical layout (Yankee, top-indexed):
//   segment[0]  top    } GREEN zone (lights on level >= 2)
//   segment[1]         }
//   segment[2]         } YELLOW zone (lights on level >= 1)
//   segment[3]         }
//   segment[4]         } RED zone (always on)
//   segment[5]  bottom }
val BOTTOM_RED = listOf(5, 4)
val MID_YELLOW = listOf(3, 2)
val TOP_GREEN = listOf(1, 0)

suspend fun applySemaphore(api: GoveeClient, level: Int) {
    api.setSegments(BOTTOM_RED, RED)                                // always on
    api.setSegments(MID_YELLOW, if (level >= 1) YELLOW else OFF)
    api.setSegments(TOP_GREEN, if (level >= 2) GREEN else OFF)
}
```

## The 6-step thermometer (for a continuous fill across 6 segments)

If you want finer granularity than a 3-zone semaphore — e.g., a volume meter:

```kotlin
fun applyThermometer(api: GoveeClient, lit: Int) {
    // lit in 0..6
    val segments = (6 - lit) until 6  // top-indexed bottom-up
    val colors = listOf(GREEN, GREEN, YELLOW, YELLOW, RED, RED)
    // zip lit segments with their gradient colors:
    val ops = segments.mapIndexed { i, seg -> seg to colors[6 - lit + i] }
    val off = (0 until (6 - lit))
    runBlocking {
        ops.forEach { (seg, c) -> api.setSegment(seg, c) }
        off.forEach { api.setSegment(it, OFF) }
    }
}
```

## OFF semantics for Govee specifically

`rgb=(0,0,0)` is **unreliable** on Govee firmware — some paths treat the packed-int `0x000000` as a no-op and silently retain the prior segment state. Use `(1,1,1)` (near-black but non-zero) for "off":

```kotlin
val OFF = Triple(1, 1, 1) // NOT (0, 0, 0)
```

On session shutdown, send an explicit "all physical segments off" — never trust prior state to clear:

```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    runBlocking { api.setSegments((0..11).toList(), OFF) }
})
```

## Anti-patterns

- ❌ `segments.forEachIndexed { i, _ -> if (i < lit) light(i) }` — top-down fill, looks like a falling bar.
- ❌ Using `segment[0..lit]` on top-indexed hardware — lights from the top, opposite of what users want.
- ❌ Single-color fill (all RED, then all YELLOW, then all GREEN) — wastes the segment count, looks like a strobe.
- ❌ `rgb=(0,0,0)` for off on Govee — silently no-ops on some firmware paths.
- ❌ Forgetting the shutdown hook — stage lights left on overnight.

## Why `bottom-up` matters

Users associate "more filled" with "more signal" — like a thermometer rising. Top-down fill looks like the bar is **draining**, which reads as "losing signal" — exactly the opposite of what you want to communicate when confidence is high.
