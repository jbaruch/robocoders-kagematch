---
name: camera-setup-javacv
description: Open and warm up a JavaCV OpenCVFrameGrabber reliably on macOS, probe for real (non-black) frames before starting the main loop, and skip virtual cameras (Insta360 Link, Snap, OBS, Continuity Camera) that hijack low indices. Use when an OpenCVFrameGrabber call succeeds but returns black/stale frames, when switching between built-in and USB webcams, or when the first ~5 seconds of a pipeline produce zero face detections.
---

# Camera Setup (JavaCV)

`OpenCVFrameGrabber(0)` on macOS frequently opens a virtual camera instead of the physical webcam. The grabber succeeds, frames come through, and detection runs against… nothing. This skill is the probe pattern that avoids that.

## The probe pattern

```kotlin
import org.bytedeco.javacv.OpenCVFrameGrabber
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.global.opencv_core.mean
import org.bytedeco.opencv.opencv_core.Mat

private const val MIN_BRIGHTNESS = 10.0
private const val WARM_UP_MS = 500L
private const val MAX_PROBE_INDEX = 5

data class CameraInfo(val index: Int, val width: Int, val height: Int, val meanBrightness: Double)

fun probeCameras(): List<CameraInfo> {
    val converter = OpenCVFrameConverter.ToMat()
    val results = mutableListOf<CameraInfo>()
    for (i in 0..MAX_PROBE_INDEX) {
        val grabber = OpenCVFrameGrabber(i)
        try {
            grabber.start()
            Thread.sleep(WARM_UP_MS) // crucial on macOS — first frames are black
            val frame = grabber.grab() ?: continue
            val mat = converter.convert(frame) ?: continue
            val brightness = mean(mat).get(0L)
            results += CameraInfo(i, mat.cols(), mat.rows(), brightness)
        } catch (_: Throwable) {
            // index not present
        } finally {
            runCatching { grabber.stop() }
        }
    }
    return results
}

fun selectRealCamera(): Int {
    val cams = probeCameras()
    // Prefer the first index whose probe frame is bright enough
    val real = cams.firstOrNull { it.meanBrightness >= MIN_BRIGHTNESS }
        ?: error("No usable camera found. Probed: $cams")
    return real.index
}
```

## What "brightness < 10" actually means

A `Mat` mean below 10 (on a 0..255 BGR scale) is essentially black:
- Cameras pointed at lens caps
- Virtual cameras with no source assigned
- Cameras the OS hasn't fully woken up

Real webcams pointed at a room average **70–150**. Even a dark room registers > 20. A `< 10` threshold is conservative and catches the common bad cases.

## macOS-specific quirks

- **TCC (camera permission)** is tied to the *responsible process* — the terminal app, IDE, or runtime that launched your JVM. Granting Terminal.app camera access does **not** grant the same to IntelliJ IDEA. Each binary needs its own approval.
- **`AVFoundation` error: "not authorized to capture video (status 0)"** — TCC denied. The user has to approve via System Settings → Privacy & Security → Camera, then re-run.
- **Plugging a USB webcam reshuffles indices.** What was index 1 yesterday is now index 2. Always probe.
- **List physical cameras:** `system_profiler SPCameraDataType` from the shell.

## Idiomatic Kotlin wrapper

```kotlin
class Camera(private val width: Int = 1280, private val height: Int = 720) : AutoCloseable {
    private val converter = OpenCVFrameConverter.ToMat()
    private val grabber: OpenCVFrameGrabber

    init {
        val idx = System.getenv("CAM")?.toIntOrNull() ?: selectRealCamera()
        grabber = OpenCVFrameGrabber(idx).apply {
            imageWidth = width
            imageHeight = height
            start()
        }
        Thread.sleep(WARM_UP_MS)
    }

    fun grab(): Mat? = converter.convert(grabber.grab() ?: return null)

    override fun close() {
        runCatching { grabber.stop() }
    }
}
```

## Anti-patterns

- ❌ `OpenCVFrameGrabber(0).start()` without probing — Insta360 / Continuity Camera / Snap silently win.
- ❌ Treating successful `start()` as "the camera works" — macOS will hand you a working virtual camera that delivers black frames.
- ❌ Skipping the 500 ms warm-up — the first 2–10 frames from a real camera are often black even when the index is correct.
- ❌ Hardcoding `imageWidth = 1920` and assuming the camera supports it — JavaCV falls back to the closest mode, often silently. Probe what you actually got: `grabber.imageWidth`.

## Useful invocation

```bash
# List physical cameras macOS knows about:
system_profiler SPCameraDataType

# Probe via the Kotlin pipeline:
CAM= ./gradlew run    # forces probe (env unset)
CAM=1 ./gradlew run   # forces a specific index
```
