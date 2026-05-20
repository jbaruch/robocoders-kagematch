# RoboCoders KotlinConf — Stage Prompts

The exact prompts both presenters type into their coding agent on stage. Vibecoding prompts come with **no plugins installed**; fixed prompts are the same text **after** the `tessl install` beat. The prompt itself never changes — what changes is the context the agent has access to.

> **Both agents type the same prompts.** Baruch into Claude Code (Opus), Viktor into Junie (Gemini Flash 3.5). The diff between agents is content for the comparison slide. The diff between vibecoding and fixed is content for the aha.

---

## Pre-flight

Before Stage 1, both agents start with **zero Tessl artifacts at the project root** — no `AGENTS.md`, no `CLAUDE.md`, no `.mcp.json`, no `.tessl/`. The `tessl.json` exists with `dependencies: {}` so the live `tessl install` beat in Stage 3 has a project to land into. `tessl list` confirms "No tiles in manifest". The IDE is open on the Kotlin Gradle project under `claude-code/ready/` (`build.gradle.kts` with Kotlin 2.3 + JDK 21).

`.env` is populated with `SHELLY_BULB_IP`, `GOVEE_API_KEY`, `GOVEE_H6056_SKU`, `GOVEE_H6056_DEVICE`, `ANTHROPIC_API_KEY`. Camera (DJI Osmo Pocket 3 USB-C webcam mode) is plugged in, faces are pre-enrolled under `./faces/baruch/` and `./faces/viktor/`.

---

## Stage 0 — Opening salvo (FIRST AHA — before the first stage)

The audience expects a Kotlin project to produce Kotlin code. With no plugins, it doesn't — the model defaults to the median answer in its training data.

### Vibecoding prompt

**Prompt (type verbatim):**

```
Write a program that turns on my Shelly Duo GU10 smart bulb. The bulb is on the LAN; its IP is in the SHELLY_BULB_IP environment variable.
```

**What the agents emit without plugins:** Python with `import requests`, or a one-line `curl` shell script, or sometimes Node with `axios`. **Anything but Kotlin.** The Gradle project around them does not move the model's hand on language choice; training-data median wins.

### Plugin install beat

```
tessl install jbaruch/kotlin-tutor
tessl list
```

The single tile installs 12 `alwaysApply` rules — six idiom rules (`prefer-val`, `data-class`, etc.) and six stack-default rules (`kotlin-stack-defaults`, `ktor-for-http`, `coroutines-for-concurrency`, `djl-for-jvm-ml`, `javacv-for-vision`, `koog-for-agents`).

### Fixed prompt (same as vibecoding)

Re-issue the IDENTICAL Stage 0 prompt. The agent now emits:

- A `build.gradle.kts` with `kotlin("jvm") version "2.3.0"`, JDK 21 toolchain, `io.ktor:ktor-client-cio` dependency
- A Kotlin file with `suspend fun main() = runBlocking { ... }`, Ktor `HttpClient(CIO)`, `client.get("http://${System.getenv("SHELLY_BULB_IP")}/color/0?turn=on&...")`
- Probably a JVM shutdown hook that turns the bulb off on Ctrl-C

**Demo beat:** one `tessl install` changed both the LANGUAGE and the LIBRARY without changing a word of the prompt. That's the headline — and we haven't even started the camera yet.

---

## Stage 1 — Face detection → bulb on/off

**Prompt:**

```
Extend the program so it only turns the bulb on while a face is in the camera, and off when no face is in frame.
```

The prompt no longer names Kotlin, JavaCV, or Ktor — `jbaruch/kotlin-tutor` already steered all three. Stage 1 is shorter and the audience SEES that the explicit-instruction load is going down as context goes up.

**Expected emission:** `OpenCVFrameGrabber` + Haar cascade + the existing Ktor client edge-triggering the bulb. No persistence, no debounce.

**Demo beat:** the bulb flickers visibly when the speaker steps to the edge of the frame. Intentional foreshadowing — the persistence/debounce gap pays off in Stage 3.

> No further `tessl install` for this stage.

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

Enrol from JPEGs in ./faces/baruch/ and ./faces/viktor/.
```

No DJL mention — `kotlin-tutor`'s `djl-for-jvm-ml` rule already steered the agent toward DJL for on-JVM ML work.

**Expected emission:** DJL `Criteria` for `face_feature` (PyTorch engine), cosine distance comparison against averaged enrollment embeddings, identity → RGB mapping, edge-triggered bulb writes.

**Demo beat:** both agents recognise both presenters cleanly. "Still a tie."

> No further `tessl install` for this stage either. Stage 2 is the bait before the hook.

---

## Stage 3 — Confidence semaphore (THE FIRST AHA)

### Vibecoding prompt

**Prompt:**

```
Drive a confidence display on the Govee H6056 light bars. Show how sure the system is
that someone known is in frame as a 3-level semaphore: bottom red (system on),
middle yellow (medium match), top green (strong match).
```

Ktor is implicit now (kotlin-tutor's `ktor-for-http` rule). The Govee H6056 name is essential — the agent needs to know *which* device, and at this stage no device-truth plugin is installed yet.

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
Decompose Stage 3 into sub-agents. One sub-agent for vision (confidence calibration),
one for IoT (driving the bars), one for evaluation (stability). Add an emotion
classifier on the other bar (Golf): happy=yellow, sad=blue, angry=red, neutral=gray,
surprise=cyan, fear=purple, disgust=green.
```

Koog is implicit (kotlin-tutor's `koog-for-agents` rule). DJL is implicit (`djl-for-jvm-ml`). The prompt is now down to "what to build", not "what to build it with".

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

- "Kotlin", "Ktor", "JavaCV", "DJL", "Koog", "Gradle Kotlin DSL", "coroutines" — these are stack defaults the `jbaruch/kotlin-tutor` plugin teaches (Stage 0 install)
- "phantom segments", "Yankee", "Golf", "12 physical" — device facts the `govee-h6056` plugin teaches
- "piecewise", "0.30", "0.65", "calibration" — the `face-recognition-calibration-djl` band
- "debounce controller", "stability filter", "1.2 s min-interval", "Dispatchers.IO" — `iot-actuator-patterns-kotlin` patterns
- "4x downscale", "frame skip", "Haar false-positives" — `vision-pipeline-foundations-kotlin` patterns
- "fresh context", "explicit skill passing", "AgentDefinition skills" — `sub-agent-delegation` meta-plugin

Each prompt names ONLY: (a) the device being targeted (Shelly bulb, Govee bars) so the agent can construct the right HTTP, and (b) the visible behavior the operator wants. **The plugins supply everything between**. If a vibecoding agent accidentally hits the right answer on a particular constant, the OTHER bugs still land — the aha holds.

As the talk progresses, **the prompts get SHORTER**, not longer. Stage 0's prompt is one sentence. Stage 1's is one sentence. Stage 2's lists colours but doesn't say how. That's the visual proof: more context, fewer instructions.

## What to do if vibecoding accidentally works

If a vibecoding sub-agent picks piecewise with right-ish constants (Sonnet 4 has been known to), don't panic. Point at the JSON output the sub-agent returned: the rationale is a hand-waving "common ML pattern" guess, not "I checked the FaceNet calibration plugin". Then show the fixed sub-agent's rationale: "the plugin tells me DJL face_feature uses this band". The audience reads the difference between **guessing right** and **knowing**.

That's actually the deeper point of the talk.
