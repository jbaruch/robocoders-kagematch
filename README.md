# RoboCoders Kotlin Edition — KotlinConf

45-minute reframe of the Arc of AI talk. Same thesis ("context, not the agent"),
new constraints: Kotlin/JVM only, 45 minutes, Junie+Gemini Flash 3.5 vs Claude Code.

## What's here

- [presentation-outline.md](./presentation-outline.md) — 10 slides + speaker notes
- [DEMO-SCRIPT.md](./DEMO-SCRIPT.md) — 4-stage progressive demo (Stage 0 merged into 1)

The original Python/Arc-of-AI demo lived in `./arc-of-ai/` during development as a reference; once the Kotlin rewrite was self-sufficient (all stages verified on hardware, plugins published to Tessl registry), the local copy was removed.

## Deltas vs Arc of AI

| Area | Arc of AI | KotlinConf |
|---|---|---|
| Slot | 75 min | 45 min |
| Agents | Claude Code (Baruch) vs Copilot (Viktor) | Claude Code vs **Junie running Gemini Flash 3.5** |
| Language | Python | **Kotlin/JVM 21** |
| Vision | `face_recognition` (dlib) + HF `transformers` ViT | **DJL** (PyTorch engine): RetinaFace + FaceNet + ViT |
| Camera | OpenCV (cv2) | **JavaCV** (`org.bytedeco:javacv-platform`) |
| HTTP | `requests` | **Ktor client** + kotlinx.serialization |
| Concurrency | threads + asyncio | **Kotlin coroutines + Flow** |
| Stage 0 | Standalone hello-world | **Merged into Stage 1** (audience can read Ktor cold) |
| Stage 2 timing | 8 min | **4 min** (single "still a tie" beat) |
| Stage 3 timing | 18 min | **12 min** (cut redundant code walkthroughs, use IDE diff) |
| Stage 4 timing | 20 min | **12 min** (trim emotion enumeration) |

## Plugin rework plan

Source plugins lived under `arc-of-ai/tiles/` during development; the new Kotlin variants now live in `./tiles/` and are published to the Tessl registry.

### Device-truth plugins (language-agnostic — clean up Python references)

| Plugin | Change |
|---|---|
| `jbaruch/govee-h6056` | Strip Python code snippets. Keep pure facts: 12 physical / 15 API / phantom 12-14, Yankee 0-5 + Golf 6-11, `segment[0]` is at top. |
| `jbaruch/shelly-duo-gu10` *(new)* | Extract mDNS name + REST shape from current Python demo. Pure facts, no language tie. |

### Calibration plugins (empirical, model-specific — re-measure)

| Plugin | Change |
|---|---|
| `jbaruch/face-recognition-calibration-djl` *(new — replaces `face-recognition-calibration`)* | FaceNet cosine distance bands: strong 0.35-0.45, borderline 0.45-0.60, reject >0.65. Piecewise confidence: `d<=0.35→1.0`, `d>=0.65→0.0`, else `(0.65-d)/0.30`. Re-measured on our hardware/lighting. |

### Code-pattern plugins (Kotlin idioms)

| Plugin | Change |
|---|---|
| `jbaruch/iot-actuator-patterns-kotlin` *(new variant)* | Debounce via `Flow.debounce`. Quantization via rounding before Ktor call. Progress-bar bottom-up + invert segment index. |
| `jbaruch/vision-pipeline-foundations-kotlin` *(new variant)* | JavaCV `OpenCVFrameGrabber` setup. Frame-skip via `Flow.sample(80.milliseconds)`. Coroutine-friendly closeable wrapping. |

### Unchanged

| Plugin | Status |
|---|---|
| `jbaruch/sub-agent-delegation` | Meta-plugin — language-agnostic, ships as-is. |

### Retired for this version

| Plugin | Reason |
|---|---|
| `jbaruch/progress-bar-ux` | Folded into `iot-actuator-patterns-kotlin` to keep the install beat to 4 commands. |
| `jbaruch/rate-limited-iot-debounce` | Folded into `iot-actuator-patterns-kotlin`. |

## Open risks

- **Junie + Gemini Flash 3.5 behavior on the hard prompts is unmeasured.** Rehearse Stage 3 vibecoding on Junie before the conf — if it accidentally produces correct code, the aha collapses. Mitigation: be ready to swap to a deliberately under-specified prompt.
- **DJL FaceNet weights**: confirm license and bundle in a fat JAR so first-run is offline-safe. Don't rely on conference Wi-Fi.
- **Sub-agent orchestration on Junie's side**: Junie's multi-agent model differs from Claude Agent SDK. Stage 4 may need to narrate the parallel rather than literally run two orchestrators side-by-side. Decide before rehearsal.
- **Stage 4 orchestrator language**: the orchestrator code (Claude Agent SDK) is still Python/TS — only the *generated* code is Kotlin. Flag this on stage so nobody thinks we cheated.

## Next steps (not yet done)

1. Build `./claude-code/ready/` skeleton: `build.gradle.kts`, `Stage1.kt`, `Stage2.kt`, `Stage3Vibecoding.kt`, `Stage3Fixed.kt`, `Stage4Vibecoding.kt`, `Stage4Fixed.kt`, `Stage4Live.kt`.
2. Re-measure FaceNet distances on the actual hardware/lighting and finalize calibration plugin numbers.
3. Pre-encode `faces/enrolled.bin` via DJL FaceNet from the existing `faces/baruch/` and `faces/viktor/` JPGs.
4. Verify Junie + Gemini Flash 3.5 reproduces each vibecoding failure mode on cold prompts.
5. Re-shoot Govee `tessl eval run` baseline → +context numbers with the Kotlin pipeline (the "27% → 100%" closing claim must hold for the new stack or be re-quoted).
