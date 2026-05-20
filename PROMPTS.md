# RoboCoders KotlinConf — Stage Prompts

The exact prompts both presenters type into their coding agent on stage. Vibecoding prompts come with **no plugins installed**; fixed prompts are the same text **after** the `tessl install` beat. The prompt itself never changes — what changes is the context the agent has access to.

> **Both agents type the same prompts.** Baruch into Claude Code (Opus), Viktor into Junie (Gemini Flash 3.5). The diff between agents is content for the comparison slide. The diff between vibecoding and fixed is content for the aha.

---

## Pre-flight

Before Stage 1, both agents start with **zero Tessl artifacts at the project root** — no `AGENTS.md`, no `CLAUDE.md`, no `.mcp.json`, no `.tessl/`. The `tessl.json` exists with `dependencies: {}` so the live `tessl install` beat in Stage 3 has a project to land into. `tessl list` confirms "No tiles in manifest". The IDE is open on the Kotlin Gradle project under `claude-code/ready/` (`build.gradle.kts` with Kotlin 2.3 + JDK 21).

`.env` is populated with `SHELLY_BULB_IP`, `GOVEE_API_KEY`, `GOVEE_H6056_SKU`, `GOVEE_H6056_DEVICE`, `ANTHROPIC_API_KEY`. Camera (DJI Osmo Pocket 3 USB-C webcam mode) is plugged in, faces are pre-enrolled under `./faces/baruch/` and `./faces/viktor/`.

---

## Stage 1 — Face detection → bulb on/off

**Prompt (type verbatim):**

```
Write a Kotlin program that turns on my Shelly Duo GU10 bulb when a face is detected
in the camera and turns it off when no face is in frame. Use JavaCV for camera capture
and face detection, and Ktor for the HTTP calls to the bulb. The bulb IP is in the
SHELLY_BULB_IP env var.
```

**Expected emission:** Haar cascade via `OpenCVFrameGrabber` + Ktor `client.get("http://$ip/color/0?...")`. No persistence, no debounce.

**Demo beat:** the bulb flickers visibly when the speaker steps to the edge of the frame. This is intentional foreshadowing — the persistence/debounce gap will be paid off in Stage 3.

> No `tessl install` for this stage.

---

## Stage 2 — Identity → bulb color

**Prompt:**

```
Extend the program so the bulb colour follows who is in frame.
  - me (Baruch) → blue
  - Viktor → red
  - both of us → purple
  - someone unknown → white
  - nobody → off

Enrol from JPEGs in ./faces/baruch/ and ./faces/viktor/. Use DJL with the face_feature
model for the embeddings.
```

**Expected emission:** DJL `Criteria` for `face_feature` (PyTorch engine), cosine distance comparison against averaged enrollment embeddings, identity → RGB mapping, edge-triggered bulb writes.

**Demo beat:** both agents recognise both presenters cleanly. "Still a tie."

> No `tessl install` for this stage either. Stage 2 is the bait before the hook.

---

## Stage 3 — Confidence semaphore (THE FIRST AHA)

### Vibecoding prompt

**Prompt:**

```
Drive a confidence display on the Govee H6056 light bars. Show how sure the system is
that someone known is in frame as a 3-level semaphore: bottom red (system on),
middle yellow (medium match), top green (strong match). Use Ktor for the cloud API.
```

**What the agents emit without plugins** (all 4 bugs land, both Claude Code and Junie):

1. `range(15)` — addresses phantom segments 12, 13, 14
2. `BOTTOM = 0..4, MID = 5..9, TOP = 10..14` — naive thirds, MID spans both physical bars
3. `conf = 1f - d / 0.6f` — textbook formula, compresses strong matches to mid band
4. Inline `client.post(...)` from the producer loop — blocks at network latency, framerate collapses to ~3 fps

On the bars: yellow band spanning across Yankee and Golf, green almost never lights, top 3 segments dark (phantoms), preview chops to single digits fps.

### Plugin install beat (live)

**Run on stage** (don't type the prompt again yet):

```
tessl install jbaruch/govee-h6056
tessl install jbaruch/face-recognition-calibration-djl
tessl install jbaruch/iot-actuator-patterns-kotlin
tessl install jbaruch/vision-pipeline-foundations-kotlin
tessl list
```

Pull up `https://tessl.io/registry/jbaruch/govee-h6056` on a second monitor. "These aren't hypothetical."

### Fixed prompt (same as vibecoding)

**Re-issue the IDENTICAL Stage 3 prompt** to the same agent with plugins now loaded. The agent emits:

1. Segments addressed only 0..11
2. Yankee 0..5, Golf 6..11 — semaphore lives on Yankee only, bottom-up via `(6 - lit) until 6`
3. Piecewise: `d ≤ 0.30 → 1.0, d ≥ 0.65 → 0.0, linear between`
4. Off-thread `DebounceController` on `Dispatchers.IO`, 1.2 s min-interval, 2-tick stability, send-latest

On the bars: clean red→yellow→green Yankee, Golf dark, 30 fps preview.

---

## Stage 4 — Sub-agents + emotion (THE SECOND AHA)

### Vibecoding prompt

**Prompt:**

```
Decompose Stage 3 into sub-agents using Koog. One sub-agent for vision (confidence
calibration), one for IoT (driving the bars), one for evaluation (stability). Add an
emotion classifier on the other bar (Golf): happy=yellow, sad=blue, angry=red,
neutral=gray, surprise=cyan, fear=purple, disgust=green. Use a DJL ONNX model for
emotion.
```

**What the agents emit without `sub-agent-delegation`:**

The parent orchestrator dispatches to 3 `Koog AIAgent` instances. None of them inherit the four plugins the parent has loaded — Koog sub-agents start with fresh context. Each one reinvents reality:

- **vision sub-agent**: re-derives the textbook `1 - d/tol` confidence (Sonnet sometimes picks piecewise — but with wrong constants like 0.40/0.80 instead of the calibrated 0.30/0.65)
- **iot sub-agent**: emits `totalSegments=15`, mid spans both bars, emotion lands on segments `[13,14,15]` (all phantom — emotion bar stays dark on stage)
- **eval sub-agent**: no stability filter, or a strict one with wrong tick rate

On the bars: emotion dark, Yankee misaligned, every bar flicker re-triggers Govee calls.

### Plugin install beat

```
tessl install jbaruch/sub-agent-delegation
```

The meta-plugin teaches the orchestrator the explicit handoff pattern: each `Koog AIAgent` gets the relevant skill rules inlined into its system prompt.

### Fixed prompt (same as vibecoding)

Re-issue the IDENTICAL Stage 4 prompt. The orchestrator now passes plugins explicitly to each sub-agent. The decisions returned:

- **vision**: `{ method: "piecewise", strongThreshold: 0.30, rejectThreshold: 0.65 }`
- **iot**: `{ totalSegments: 12, zones: [Yankee BOTTOM=[4,5], MID=[2,3], TOP=[0,1], Golf=[6..11]], offRgb: [1,1,1] }`
- **eval**: `{ stabilityMethod: "n_tick", nTicks: 2, tickMs: 400 }`

On the bars: Yankee semaphore correct, Golf shows emotion colour matching the speaker's face, no phantom calls, smooth 30 fps.

---

## Closing — the number

Before the crystallisation slide:

```
tessl eval view <govee-h6056-eval-run-id>
```

The output shows `Baseline (without context) avg: 27% — With context avg: 100%`. Speaker reads the number out loud. **That's the headline of the closing slide.**

Companion evals for the other Kotlin plugins (averaging +50 lift) live at:
- `jbaruch/iot-actuator-patterns-kotlin` (50→100)
- `jbaruch/face-recognition-calibration-djl` (46→100)
- `jbaruch/vision-pipeline-foundations-kotlin` (53→100)
- `jbaruch/shelly-duo-gu10` (79→100)

---

## Notes on prompt design

The prompts deliberately do **not** mention:

- "phantom segments", "Yankee", "Golf", "12 physical" — these are device facts the `govee-h6056` plugin teaches
- "piecewise", "0.30", "0.65", "calibration" — these are the `face-recognition-calibration-djl` band
- "debounce controller", "stability filter", "1.2 s min-interval", "Dispatchers.IO" — `iot-actuator-patterns-kotlin` patterns
- "4x downscale", "frame skip", "Haar false-positives" — `vision-pipeline-foundations-kotlin` patterns
- "fresh context", "explicit skill passing", "AgentDefinition skills" — `sub-agent-delegation` meta-plugin

The prompts describe **what the operator wants visible on the bars**, not **how to achieve it**. The plugins supply the how. If a vibecoding agent accidentally hits the right answer on a particular constant, the OTHER three bugs still land — the aha holds.

## What to do if vibecoding accidentally works

If a vibecoding sub-agent picks piecewise with right-ish constants (Sonnet 4 has been known to), don't panic. Point at the JSON output the sub-agent returned: the rationale is a hand-waving "common ML pattern" guess, not "I checked the FaceNet calibration plugin". Then show the fixed sub-agent's rationale: "the plugin tells me DJL face_feature uses this band". The audience reads the difference between **guessing right** and **knowing**.

That's actually the deeper point of the talk.
