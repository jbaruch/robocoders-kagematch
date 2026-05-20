# vision-pipeline-foundations-kotlin

A [Tessl](https://tessl.io) plugin encoding hygiene for JavaCV + DJL vision pipelines on Kotlin/JVM — the Kotlin equivalent of [jbaruch/vision-pipeline-foundations](https://github.com/jbaruch/vision-pipeline-foundations), which targets Python OpenCV.

## What this plugin provides

| Kind | Name | Purpose |
|---|---|---|
| Skill | `camera-setup-javacv` | Open and warm up `OpenCVFrameGrabber` reliably. Probe pattern that skips virtual cameras (Insta360, Snap, OBS, Continuity) that hijack low indices on macOS. Includes brightness-based detection of "open succeeded but frames are black". |
| Skill | `frame-skip-policy-kotlin` | Run heavy inference (Haar, DJL face_feature, ViT emotion) at a fraction of capture rate. **Every 3rd frame for face recognition, every 30th for emotion.** Includes the 4× downscale pattern that makes Haar both faster AND less false-positive-prone. |
| Rule  | `vision-pipeline-foundations-kotlin-rules` | Camera setup, frame skip, coroutine architecture, anti-patterns. |

## Why it exists

`OpenCVFrameGrabber(0)` on macOS frequently opens a virtual camera (Insta360 Link, Continuity Camera) instead of the physical webcam. The grabber succeeds, frames flow, detection runs against… nothing. The probe pattern in this plugin avoids that.

And: running face detection at full 30 fps + emotion classification at 30 fps wastes 3–10× CPU for zero perceptible improvement. The frame-skip policy here puts detection at ~10 Hz and emotion at ~1 Hz — matched to how fast those signals actually change in the real world.

## Install

```bash
tessl install jbaruch/vision-pipeline-foundations-kotlin
```

## License

MIT — see [LICENSE](LICENSE).
