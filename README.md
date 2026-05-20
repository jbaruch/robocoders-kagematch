# RoboCoders KotlinConf — Judgment Day

A 45-minute mode-f live-coding showdown for KotlinConf 2026.
**Baruch Sadogursky** (Claude Code on Opus 4.7) vs **Viktor Gamov** (Junie running Gemini Flash 3.5).
Five stages, same prompts on both sides. Same hardware. The variable being demonstrated isn't the model — it's the context.

Same thesis as our [Arc of AI](https://www.arcofai.com) talk earlier this year:

> Both agents can write Kotlin that runs. Neither can write Kotlin that is RIGHT — until you engineer the context they both inherit. And when you delegate, you have to engineer the context PROPAGATION too — because by default, sub-agents get nothing.

## What's in this repo

| Path | Purpose |
|---|---|
| [presentation-outline.md](./presentation-outline.md) | 10 slides + speaker notes, 45-min pacing |
| [DEMO-SCRIPT.md](./DEMO-SCRIPT.md) | Full 5-stage script with audience energy map |
| [PROMPTS.md](./PROMPTS.md) | Verbatim prompts both presenters type on stage |
| [claude-code/ready/](./claude-code/ready/) | Kotlin/JVM runtime for Baruch's side. All 5 stages, all verified on hardware. |
| [faces/](./faces/) | Enrollment photos for face recognition |
| `tessl.json` | Tessl project marker. `dependencies: {}` so the live install beat does real work. |

Viktor's `junie/ready/` side lives elsewhere — Viktor's repo.

## Hardware on stage

- **Camera**: DJI Osmo Pocket 3 (USB-C webcam mode)
- **Bulb**: Shelly Duo GU10 RGBW (LAN HTTP, ~30 ms latency)
- **Light bars**: 2× Govee H6056 Flow Plus (cloud REST, ~7 req/min sustained)
- **Travel router** to isolate the venue Wi-Fi

## Software stack

- **JDK 21**, **Kotlin 2.3**, Gradle Kotlin DSL
- **JavaCV 1.5.13** (OpenCV bindings) for camera capture + Haar face detection
- **DJL 0.36** (PyTorch engine) for face_feature (ArcFace) embeddings; ONNX runtime for FER+ emotion
- **Ktor 3.4.3** client + server (preview MJPEG stream)
- **kotlinx-coroutines 1.10.2** + Flow for the pipeline
- **Koog 0.7.3** ([JetBrains Kotlin-native AI agent framework](https://github.com/JetBrains/koog)) for Stage 4 sub-agent orchestration

## Tessl plugins (the context engineering layer)

All published. Each repo has its own README + evals. The `tessl.json` in this repo starts empty so the live demo flow shows real install activity.

| Plugin | Source repo | What it teaches |
|---|---|---|
| [`jbaruch/kotlin-tutor`](https://github.com/jbaruch/kotlin-tutor) | language + stack defaults | Kotlin 2.3, JDK 21, Gradle Kotlin DSL; Ktor for HTTP; coroutines + `Dispatchers.IO`; DJL for ML; JavaCV for vision; Koog for agents; plus idiom rules (val, data class, scope fns, Kotest). **Used in Stage 0**. |
| [`jbaruch/govee-h6056`](https://github.com/jbaruch/govee-h6056) | language-agnostic device facts | Phantom segments, Yankee/Golf mapping, `segment[0]` is top, `rgb(1,1,1)` for off, 1.2 s cloud min-interval |
| [`jbaruch/shelly-duo-gu10`](https://github.com/jbaruch/shelly-duo-gu10) | LAN bulb facts + Kotlin/Ktor + JmDNS | REST shape, mDNS bind-to-non-loopback-IPv4 gotcha, 0.2 s LAN min-interval |
| [`jbaruch/face-recognition-calibration-djl`](https://github.com/jbaruch/face-recognition-calibration-djl) | empirical FaceNet calibration | Piecewise confidence `d ≤ 0.30 → 1.0, d ≥ 0.65 → 0.0`, distance bands, RGB vs BGR |
| [`jbaruch/iot-actuator-patterns-kotlin`](https://github.com/jbaruch/iot-actuator-patterns-kotlin) | Kotlin coroutines patterns | One-coroutine-per-device debounce, 0.2 s LAN / 1.2 s cloud, `Dispatchers.IO` (not `Default`), target quantization, bottom-up progress-bar |
| [`jbaruch/vision-pipeline-foundations-kotlin`](https://github.com/jbaruch/vision-pipeline-foundations-kotlin) | JavaCV + DJL hygiene | macOS camera probe (skip virtuals), 4× downscale for Haar, frame-skip every 3rd / 30th |
| [`jbaruch/sub-agent-delegation`](https://github.com/jbaruch/sub-agent-delegation) | meta-plugin | Sub-agents start fresh; pass skills explicitly via `AgentDefinition(skills=[...])`; validate with echo handshake |

### Closing eval number

`tessl eval run` against `jbaruch/govee-h6056`: **baseline 27% → with context 100%** on Claude Sonnet 4.6, 3 scenarios. Full eval at [tessl.io/eval-runs/019e477c-22c7-75cc-a571-b342dcb578d3](https://tessl.io/eval-runs/019e477c-22c7-75cc-a571-b342dcb578d3).

The other 5 Kotlin plugins also ship with leak-reviewed evals: average baseline ~57%, average with-context 100%.

## How to run (from a clean clone)

```bash
git clone https://github.com/jbaruch/robocoders-kagematch.git
cd robocoders-kagematch

# Populate .env (NOT committed)
cat > .env <<'EOF'
SHELLY_BULB_IP=192.168.8.135
GOVEE_API_KEY=<your-key>
GOVEE_H6056_SKU=H6056
GOVEE_H6056_DEVICE=<your-device-id>
ANTHROPIC_API_KEY=<your-key>   # Stage 4 only
EOF

cd claude-code/ready

# Stage 1: face detection → bulb
MAX_SECONDS=60 CAM=1 ./gradlew runStage1

# Stage 2: identity → bulb colour
MAX_SECONDS=60 CAM=1 ./gradlew runStage2

# Stage 3 — vibecoding (broken) then install plugins then re-run "fixed"
MAX_SECONDS=60 CAM=1 ./gradlew runStage3Vibecoding
tessl install jbaruch/govee-h6056 jbaruch/face-recognition-calibration-djl \
              jbaruch/iot-actuator-patterns-kotlin jbaruch/vision-pipeline-foundations-kotlin
MAX_SECONDS=60 CAM=1 ./gradlew runStage3Fixed

# Stage 4 — sub-agents, vibecoding then meta-plugin then fixed
MAX_SECONDS=60 CAM=1 ./gradlew runStage4Vibecoding
tessl install jbaruch/sub-agent-delegation
MAX_SECONDS=60 CAM=1 ./gradlew runStage4Fixed

# Stage 4 Live — continuous pipeline with all plugins applied
MAX_SECONDS=90 CAM=1 ./gradlew runStage4Live
```

Preview at [http://localhost:8080/](http://localhost:8080/) when any stage is running (MJPEG over Ktor server).

## Deltas vs Arc of AI

| Area | Arc of AI | KotlinConf |
|---|---|---|
| Slot | 75 min | 45 min |
| Agents | Claude Code (Baruch) vs Copilot (Viktor) | Claude Code (Opus 4.7) vs **Junie running Gemini Flash 3.5** |
| Language | Python | **Kotlin/JVM 21** |
| Vision | `face_recognition` (dlib) + HF `transformers` ViT | **DJL** (PyTorch + ONNX): face_feature + emotion-ferplus |
| Camera | OpenCV (cv2) | **JavaCV** (`org.bytedeco:javacv-platform`) |
| HTTP | `requests` | **Ktor client** + kotlinx.serialization |
| Concurrency | threads + asyncio | **Kotlin coroutines + Flow** |
| Sub-agent orchestrator | Claude Agent SDK (Python/TS) | **Koog** (JetBrains, Kotlin-native — no language cheat) |
| Stage 0 | Standalone hello-world | **Merged into Stage 1** (audience can read Ktor cold) |

## Pre-flight checklist for the conf

- [ ] Both laptops on JDK 21, Kotlin 2.3 cached, Gradle wrapper warmed
- [ ] DJL native libs cached: `~/.djl.ai/cache/` populated (FaceNet, FER+) — run any stage once before going on stage
- [ ] Govee bars paired, Shelly bulb at static IP, travel router on
- [ ] **Zero Tessl context at the project root before Stage 3** — verify with:
  ```bash
  ls AGENTS.md CLAUDE.md .mcp.json .tessl 2>/dev/null   # should print nothing
  cat tessl.json | jq .dependencies                      # should print {}
  ```
  If any of those exist, run `rm -f AGENTS.md CLAUDE.md .mcp.json && rm -rf .tessl` to reset. The Stage 3/4 aha depends on the agent having no plugin context until the live `tessl install` beat creates it.
- [ ] `faces/baruch/` and `faces/viktor/` populated, or live re-enrollment ready
- [ ] T-shirts in the room
- [ ] Die Hard "Agent Johnson" image licensed/sourced

## License

MIT (where applicable). Plugin repos each carry their own LICENSE file.
