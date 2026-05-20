# Vision Pipeline Foundations — Kotlin Rules

Hygiene for JavaCV + DJL vision pipelines on Kotlin/JVM.

## Camera setup (→ `camera-setup-javacv` skill)

- **Index 0 is often a virtual camera.** Insta360 Link, Snap Camera, OBS Virtual Camera, Continuity Camera, EpocCam — these claim the low indices on macOS. The physical webcam frequently lands on index 1, 2, or higher. **Probe, don't hardcode.**
- **Detect virtual/black cameras by frame brightness.** `mean(frame) < 10` after a 500 ms warm-up = skip this index.
- **`OpenCVFrameGrabber.start()` returning successfully + black frames is normal on macOS.** Wait 500 ms, then probe with an actual `grab()` before trusting the source.
- **macOS camera enumeration:** `system_profiler SPCameraDataType` lists physical cameras. Cross-reference with the JavaCV index your probe finds.
- **First-run JavaCV native extraction** pulls hundreds of MB of platform-specific JNI libs. Pre-warm by running any JavaCV command once before demo time. Don't trust conference Wi-Fi.

## Frame-skip policy (→ `frame-skip-policy-kotlin` skill)

- **Face recognition: every 3rd frame** (≈10 Hz at 30 fps capture).
- **Emotion classification: every 30th frame** (≈1 Hz at 30 fps capture). Emotions change slowly.
- **General object detection: every 5th frame** unless the object moves fast.
- **Always downscale before heavy inference.** 4× downscale (`1280×720 → 320×180`) for Haar face detection — 4× speed-up AND suppresses false-positive faces (background patterns at full resolution look face-like to Haar; downscaled, they don't).
- **Persist the last detection result** across skipped frames so the preview overlay stays current. Don't blank out boxes on skip frames.

## Coroutine architecture

- Camera grab + preview encode + face/emotion inference belong on **one coroutine** (or use a `Flow<Frame>` with `.sample()` for frame-skip). Keep the producer loop sequential.
- HTTP / IoT calls go to **separate controller coroutines** on `Dispatchers.IO`. See `iot-actuator-patterns-kotlin`'s debounce-controller skill.

## Anti-patterns

- ❌ `OpenCVFrameGrabber(0).start()` and trusting index 0 is the webcam — Insta360 Virtual Camera quietly hijacks it on most modern Macs.
- ❌ Running face detection at full 30 fps — wastes 3× CPU for the same demo-visible result.
- ❌ Skipping the preview overlay update on non-detect frames — face boxes vanish, looks broken.
- ❌ Running detection on full-resolution frames — slower AND noisier (more Haar false positives).
- ❌ Trying to detect AND classify emotion every frame — emotion classifiers are heavy (ViT, ~50 ms each); they don't need 30 Hz.

Full skill content: `skills/camera-setup-javacv/SKILL.md`, `skills/frame-skip-policy-kotlin/SKILL.md`.
