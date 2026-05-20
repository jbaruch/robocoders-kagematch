# RoboCoders Kotlin Edition: Progressive Demo Script

A 4-stage escalation (Stage 0 hello-world merged into Stage 1).
Each stage raises complexity.
Stages 1-2 work **without** context — that's the "vibecoding works" baseline.
Stages 3-4 are where vibecoding breaks and context engineering saves the demo.

Each stage has:

- **The prompt** (what we type to the agent)
- **What vibecoding produces** (what happens without plugins)
- **The context miss** (the specific thing the agent doesn't know)
- **The plugin** (what structured context would contain)
- **The aha moment** (what the audience sees/realizes)

> **Vocabulary note:** These are called **plugins** on stage (Tessl's current name).
> Older material may call them "tiles" — same thing.

> **Language note:** Every line of demo code is Kotlin on JVM 21.
> Stack: JavaCV (OpenCV bindings) for camera, DJL (PyTorch engine) for face detect/embed/emotion,
> Ktor client + kotlinx.serialization for IoT REST, Kotlin coroutines + `Flow` for the frame pipeline.

---

## Stage 1 — "Bulb on when face in frame" (vibecoding works, cracks showing)

**Prompt:** `Write a Kotlin program that turns on my Shelly bulb when a face is in the camera, off when no face. Discover the bulb via mDNS. Use JavaCV for capture, DJL for detection, Ktor for HTTP.`

**What vibecoding produces:**

```kotlin
// build.gradle.kts dependencies (truncated)
// org.bytedeco:javacv-platform
// ai.djl:api, ai.djl.pytorch:pytorch-engine, ai.djl.pytorch:pytorch-model-zoo
// io.ktor:ktor-client-cio, ktor-client-content-negotiation
// org.jmdns:jmdns

suspend fun main() = coroutineScope {
    val bulbIp = discoverShellyMdns()
    val client = HttpClient(CIO)
    val detector = Criteria.builder()
        .setTypes(Image::class.java, DetectedObjects::class.java)
        .optModelUrls("djl://ai.djl.pytorch/retinaface")
        .build().loadModel().newPredictor()

    val grabber = OpenCVFrameGrabber(0).apply { start() }
    while (isActive) {
        val img = ImageFactory.getInstance().fromImage(grabber.grab())
        val faces = detector.predict(img).items()
        val on = faces.isNotEmpty()
        client.get("http://$bulbIp/color/0?turn=${if (on) "on" else "off"}&red=255&green=255&blue=255")
    }
}
```

**What happens on stage:** Face in frame → bulb on. Out → bulb off. Works.

**But the crack:** Detection is imperfect.
Step to frame edge — bulb flickers.
Partial occlusion — bulb strobes.

**Stage direction:** Baruch deliberately steps to the frame edge for ~5 seconds.
The flicker must be visible.
Do not narrate yet.

**Narration (after the flicker is visible):**
> [BARUCH]: "See that flicker?
> Neither of us handled detection intermittency.
> Both demos work, neither is robust.
> Remember this — it's going to matter in about 8 minutes."

**No context fix yet.** Foreshadowing only.

> Why we skipped a standalone Stage 0 hello-world: KotlinConf audience can read a Ktor `HttpClient.get()` call in their sleep.
> The baseline tax is unnecessary. We start at "face detection plus IoT" and still get there in under 5 minutes.

---

## Stage 2 — "Face recognition → identity color" (basic, mostly works)

**Prompt:** `Identify WHO is in frame using FaceNet embeddings via DJL. Baruch → blue. Viktor → red. Both → purple. Unknown → white. No one → off. Enroll from faces/baruch/*.jpg and faces/viktor/*.jpg.`

**What vibecoding produces:**

```kotlin
// One-time enrollment (cached to faces/enrolled.bin)
val embedder = Criteria.builder()
    .setTypes(Image::class.java, FloatArray::class.java)
    .optModelUrls("djl://ai.djl.pytorch/facenet")
    .build().loadModel().newPredictor()

val enrolled: Map<String, FloatArray> = listOf("baruch", "viktor").associateWith { name ->
    Path("faces/$name").listDirectoryEntries("*.jpg")
        .map { embedder.predict(ImageFactory.getInstance().fromFile(it)) }
        .reduce { a, b -> FloatArray(a.size) { i -> a[i] + b[i] } }
        .let { sum -> FloatArray(sum.size) { i -> sum[i] / sum.size } }
}

// Live loop
val live = embedder.predict(faceCrop)
val (who, dist) = enrolled.minBy { cosineDistance(live, it.value) }
    .let { it.key to cosineDistance(live, it.value) }
val color = when {
    dist > 0.6f -> Color.WHITE
    who == "baruch" -> Color.BLUE
    who == "viktor" -> Color.RED
    else -> Color.WHITE
}
shelly.setColor(color)
```

**What happens on stage:**

- Bulb blue when Baruch in frame ✓
- Bulb red when Viktor in frame ✓
- Works at ~25fps with frame-skip every 3rd frame via `Flow.sample(120.milliseconds)`

**Narration:**
> [BARUCH]: "Bulb follows identity.
> Both agents nailed this too.
> Still a tie." > [VIKTOR]: "I told you we could go home." > [BARUCH]: "Watch stage 3.
> Now the wheels come off."

**Still no context fix.** Tension builds.
Two stages "won" by both agents — audience thinks it's a wash.
We're baiting the hook.

> **Setup-only friction** (DJL native libs, first-run model download, ARM vs x86 PyTorch jars) is **not** a stage beat.
> Cache `~/.djl.ai/` ahead of time.
> Save for Q&A.

---

## Stage 3 — "Confidence meter" (THE FIRST AHA — context misses pile up)

**Prompt:** `Light up Govee H6056 segments on one bar proportional to recognition confidence. Bottom red (system on), middle yellow (medium), top green (strong). Use Ktor.`

This is where **four real context misses** hit simultaneously.
All four are language-agnostic — they survived the Python → Kotlin port intact.

### Context Miss #1: Phantom segments

**What the agent does:**

- Reads Govee API docs: `segment: array, min 1, max 15`
- Writes Kotlin: `val segments = (0 until 15).map { it }`
- All 15 get RGB assigned, all 15 return HTTP 200.
- **Only 12 physically light up.** Segments 12-14 are phantom.

**The aha:** The Kotlin code SUCCEEDS.
Every Ktor call returns 200.
No exceptions, no warnings.
But physically, three commands vanish.
"Wait, I count 12 segments.
My code commanded 15."

> [BARUCH]: "Viktor, count the segments." > [VIKTOR]: *counts* "...12." > [BARUCH]: "I commanded 15.
> API says 15.
> Govee said 200 OK to 15.
> Reality says 12.
> This is what 'confident but wrong' looks like."

### Context Miss #2: Segment-to-bar mapping

**What the agent does:**

- Splits 15 segments across 2 bars: "0-7 on bar A, 8-14 on bar B" (obvious split)
- Reality: 0-5 = Yankee, 6-11 = Golf, 12-14 phantom.
- Confidence meter spans BOTH bars instead of just one.

### Context Miss #3: Confidence formula calibration

**What the agent does:** textbook `val conf = 1f - cosineDistance / tolerance` (tolerance=0.6).
Cosine distance for a GOOD FaceNet match: ~0.45 → confidence 0.25 → bar fills only 25%.
System looks "unsure of itself" with perfect recognition.

> **DJL/FaceNet specific:** the band shifted from dlib's `0.30–0.55` to FaceNet's `0.35–0.65`.
> The context miss is identical (textbook formula).
> The calibration plugin now ships as `face-recognition-calibration-djl` with updated thresholds.

### Context Miss #4: Top-down inversion

The agent maps `segment[0]` to the bottom because that's natural ordering.
The hardware's `segment[0]` is at the **top**.
"Bottom red" lights up the top.

### Presentation direction — show the DIFF, not the whole file

Don't walk through 100 lines of `Stage3Vibecoding.kt` on screen.
Pull up an IDE diff against `Stage3Fixed.kt` and highlight exactly the 3-5 lines per miss that change.
The audience reads 10 lines, not 100.

Have a **side-by-side confidence table** ready to flash on screen — same data, two formulas:

```
 cosine_d |  vibecoding |  plugin  |  Yankee bar (bottom-up)
------------------------------------------------------------
   d=0.35 |    0.42 |    1.00  |  [██████]
   d=0.45 |    0.25 |    0.83  |  [·█████]
   d=0.50 |    0.17 |    0.67  |  [··████]
   d=0.55 |    0.08 |    0.50  |  [···███]
   d=0.65 |    0.00 |    0.17  |  [·····█]
```

That table lands the first aha in 10 seconds with zero code reading.

### The plugins (what context engineering looks like)

```yaml
# plugin: govee-h6056 (language-agnostic, cleaned)
device: Govee Flow Plus Light Bars (H6056)
physical: 12 segments (0-11). Segments 12-14 are phantom.
bars:
  yankee: 0-5   # one physical bar
  golf:   6-11  # other physical bar
ordering: segment[0] is at the TOP of each bar
```

```yaml
# plugin: face-recognition-calibration-djl (NEW)
model: facenet (djl-pytorch model zoo)
distance: cosine
typical_distances:
  strong:     0.35-0.45
  borderline: 0.45-0.60
  reject:     >0.65
confidence:
  d<=0.35 -> 1.0
  d>=0.65 -> 0.0
  else    -> (0.65-d)/0.30
```

```yaml
# plugin: iot-actuator-patterns-kotlin (Kotlin variant)
debounce:
  api: kotlinx.coroutines.flow.debounce
  recipe: Flow<FaceEvent>.debounce(150.milliseconds)
quantization:
  recipe: round confidence to nearest 0.05 to coalesce Govee API calls
progress_bar:
  direction: bottom-up
  gradient: red → yellow → green
  ordering: invert segment index when writing
```

**Re-run with plugins installed.** Semaphore lights correctly: RED bottom (system on), YELLOW mid (medium match), GREEN top (strong match).
Only Yankee, Golf stays dark.
20+ fps, no strobe.
Face persistence absorbs detection dropouts.

### Live install beat (30 seconds, do it on stage)

Install all four plugins at once — correctness AND liveness fixed in one beat:

```bash
tessl install jbaruch/govee-h6056
tessl install jbaruch/face-recognition-calibration-djl
tessl install jbaruch/iot-actuator-patterns-kotlin
tessl install jbaruch/vision-pipeline-foundations-kotlin
tessl list
```

Open `https://tessl.io/registry/jbaruch/govee-h6056` on a second window.

> [BARUCH]: "These aren't hypothetical.
> Public registry, versioned, shipping.
> I wrote them once when I calibrated this hardware.
> The NEXT engineer who touches Govee inherits the fix.
> That's the difference between 'vibecoding' and 'context engineering'."

[INVERSE MR. FUSION CALLBACK]: "Modern AI converts energy into trash — unless you tell it what 'not trash' looks like.
Plugins are how you tell it."

---

## Bridge — The Context Inventory (~30 seconds)

One visual between Stage 3 and Stage 4.
Four columns, one per bug we just fixed.
See `./demo-run/context-inventory.md` (TBD) for the layout.
Purpose: let the audience mentally tag "a plugin is the unit of context."

Leave it on screen while the terminal is cleared for Stage 4.

---

## Stage 4 — "Emotion via sub-agents" (SECOND AHA — delegation erases everything)

**Prompt:** `Decompose Stage 3 into sub-agents: one for vision, one for IoT, one for evaluation. Add emotion classification on the Golf bar via DJL ViT: happy=yellow, sad=blue, angry=red, neutral=gray, surprise=cyan, fear=purple, disgust=green.`

### What happens: everything we just fixed BREAKS

The parent orchestrator spawns three sub-agents.
None inherit the Stage-3 plugins.

```
Parent agent           ← has the 4 plugins from Stage 3
├── vision sub-agent   ← fresh context, no plugins
├── iot sub-agent      ← fresh context, no plugins
└── eval sub-agent     ← fresh context, no plugins
```

Each sub-agent reinvents reality (now writing Kotlin instead of Python, but with the same regressions):

- **vision**: textbook confidence formula (`1 - cos_d/tol`) instead of piecewise → confidence reads 0.25 for a strong match
- **iot**: addresses segments 0-14 (including phantom), splits bars 0-7/8-14 (wrong), top-down fill — the concept of "Golf for emotion" collapses because the sub-agent doesn't know the Yankee/Golf mapping
- **eval**: no stability filter → reports STROBING

The audience sees the bars go haywire.
Every Stage-3 gain is visibly gone.

> [BARUCH]: *(stares at chaos)* "We spent 12 minutes getting this right. > I wrote the plugins. > The parent agent READ the plugins. > Then it spawned sub-agents. > And every sub-agent is doing the 2024 version of this code."

### Context Miss #5: Sub-agents start with FRESH context

**What's actually documented** ([Claude Agent SDK docs](https://code.claude.com/docs/en/agent-sdk/subagents)):
> "A subagent's context window starts fresh.
> The only channel from parent to subagent is the Agent tool's prompt string."

**Sub-agents DO inherit:** CLAUDE.md, tool definitions, MCP servers.
**Sub-agents DO NOT inherit:** parent conversation, parent skills (unless explicitly listed), accumulated state.

The same shape of issue exists in Junie's sub-task / multi-agent orchestration — when Viktor's side spawns helpers, they don't inherit Junie's loaded context either.
*(If Junie's orchestration model differs materially by stage time, narrate it; otherwise the parallel holds.)*

Documented gotchas (linked in shownotes):

- `context: fork` means ISOLATED blank, not parent-inherit
- AskUserQuestion silently unavailable in sub-agents
- Sub-agents silently default to a different model

### The meta-plugin: `sub-agent-delegation`

```bash
tessl install jbaruch/sub-agent-delegation
```

```yaml
# plugin: sub-agent-delegation (language-agnostic, unchanged)
the_only_channel: prompt string
does_NOT_inherit: skills, history, accumulated state
DOES_inherit: CLAUDE.md, MCP servers, tool definitions

explicit_skill_passing:
  agent_sdk: AgentDefinition(skills=["govee-h6056", ...])
  task_tool: inline skill content in the prompt

validation_protocol: |
  Sub-agent's first action: echo the skills it received.
  Fail loudly if missing.

cross_agent_truth: put it in CLAUDE.md
scope-restricted truth: put it in a skill/plugin
```

### The fix — install `sub-agent-delegation` meta-plugin

```bash
tessl install jbaruch/sub-agent-delegation
```

The meta-plugin teaches the parent to EXPLICITLY pass skills.
For the Kotlin demo, the orchestration code (still TypeScript/Python via Claude Agent SDK) hands the sub-agents their plugin context — and those sub-agents emit corrected Kotlin:

```python
# orchestrator (Python/TS via Claude Agent SDK — language of the orchestrator
# is independent of the language of the code it generates)
AgentDefinition(
    description="Drive Govee bars with plugin ground truth. Emit Kotlin.",
    prompt=ECHO_PREAMBLE + "... task ...",
    skills=[
      "govee-h6056",
      "iot-actuator-patterns-kotlin",
      "face-recognition-calibration-djl"
    ],
    model="claude-sonnet-4-6",
)
```

Each sub-agent's **first output** is a skills-echo handshake: `{"skills_echo": ["govee-h6056", ...]}`.
Parent validates and aborts on mismatch.

**Re-run.** Confidence semaphore correct on Yankee.
Emotion colors on Golf.
No phantom segments, no bar-split, no strobe.
All Stage-3 gains restored because the plugins were explicitly handed off.

**Live continuous pipeline:** `Stage4Live.kt` runs both bars simultaneously — Yankee semaphore + Golf emotion colors change in real-time as you make faces.
Single coroutine pipeline:

```kotlin
frameFlow                                    // Flow<Frame> from JavaCV
    .sample(80.milliseconds)                 // frame-skip (vision-pipeline-foundations-kotlin)
    .map { frame -> detectAndEmbed(frame) }  // RetinaFace + FaceNet via DJL
    .map { it.copy(emotion = emotion(it)) }  // ViT emotion via DJL
    .debounce(150.milliseconds)              // stability (iot-actuator-patterns-kotlin)
    .onEach { state ->
        coroutineScope {
            launch { govee.setYankee(state.confidenceSemaphore()) }
            launch { govee.setGolf(state.emotionColor()) }
            launch { shelly.setColor(state.identityColor()) }
        }
    }
    .collect()
```

**Narration:**
> [BARUCH]: "Three agents.
> Shared plugins.
> EXPLICITLY passed through a meta-plugin." > [VIKTOR]: "Context engineering for the orchestrator itself." > [BARUCH]: "Context engineering isn't 'write better prompts.'
> It's infrastructure.
> Your CLAUDE.md is cross-agent ground truth.
> Your plugins are scoped, composable artifacts.
> Your meta-plugin teaches your orchestrator how to hand those artifacts to the agents that need them." > [BARUCH]: "Last year's RoboCoders was about WHICH tool.
> This year's is about what the tools share."

---

## Closing crystallization

One slide.
Two columns:

```
2023-2025: VIBECODING            |  2026+: CONTEXT ENGINEERING
---------------------------------|--------------------------------
Prompt → hope                    |  Plugins → agents → verify
Stack Overflow                   |  Versioned, shared plugins
Single agent                     |  Orchestrated sub-agents
"It works on my machine"         |  "It works with my plugins"
Silent semantic failures         |  Known empirical calibrations
```

**Three Monday actions:**

1. Package one team Kotlin convention as a plugin (`tessl tile new`).
2. Replace one ad-hoc prompt with a versioned spec.
3. Measure one agent workflow with `tessl eval run` — numbers, not vibes.
   (Our govee-h6056 plugin: 27% baseline → 100% with context. That's the number.)

---

## Visual progression — audience energy map (45 min)

| Stage  | Time   | Audience energy | What they're feeling                                                               |
|--------|--------|-----------------|------------------------------------------------------------------------------------|
| Open   | 4 min  | warming         | Title, bios, side-pick, Die Hard gag.                                              |
| 1      | 5 min  | mild amusement  | "Kotlin face detect + bulb. Easy. Small flicker."                                  |
| 2      | 4 min  | engaged         | "It knows us."                                                                     |
| 3      | 12 min | **AHA #1**      | "It said success but only lit 12 of 15? Terrifying."                               |
| bridge | 1 min  | absorption      | "Plugins. Versioned. Installable."                                                 |
| 4      | 12 min | **AHA #2**      | "Delegation erased everything. Sub-agents regress without plugin handoff."         |
| Close  | 7 min  | resolution      | "Layered context: CLAUDE.md + plugins + delegation meta-plugin."                   |

Total: 45 min. Q&A separate.

---

## The two aha moments

1. **Vibecoding loses silently.** (Stage 3) — Every Ktor call returned 200 on commands that did nothing.
   The agent was confident and wrong.
   Four plugins fix correctness + liveness in one beat.
2. **Delegation erases everything.** (Stage 4) — The moment you spawn a sub-agent, you're back to zero.
   The concept of "emotion on Golf" collapses because the iot sub-agent has no idea what Golf IS.
   Fix: meta-plugin that teaches the orchestrator explicit handoff.

---

## Pre-flight checklist

Hardware:

- [ ] Shelly bulb powered + reachable at static IP (record on a sticky on the laptop)
- [ ] Govee H6056 bars powered + paired
- [ ] DJI Osmo Pocket 3: plugged in, webcam mode selected on device, auto-off disabled
- [ ] Travel router up, both laptops on it

Software (per terminal — both Baruch and Viktor):

```bash
cd <claude-code|junie>/live
./gradlew --no-daemon assemble   # warm Gradle, native libs
```

- [ ] JDK 21 active (`java -version`)
- [ ] Camera index probe Kotlin one-liner: `OpenCVFrameGrabber(0).apply { start(); grab(); stop() }` — note today's index
- [ ] `faces/enrolled.bin` present (pre-computed FaceNet embeddings, skips first-run encoding)
- [ ] DJL native libs cached: `~/.djl.ai/cache/` populated for RetinaFace + FaceNet + ViT
- [ ] **No Tessl artifacts in the project root**: `ls AGENTS.md CLAUDE.md .mcp.json .tessl 2>/dev/null` prints nothing, `cat tessl.json | jq .dependencies` prints `{}`. `tessl list` shows "No tiles in manifest". If artifacts leaked in from a previous run: `rm -f AGENTS.md CLAUDE.md .mcp.json && rm -rf .tessl`.
- [ ] Terminal/IDE font size bumped for the back row
- [ ] Side-by-side IDE diff view rehearsed for Stage 3

Backups:

- [ ] `claude-code/ready/` has all validated stage scripts as offline reference
- [ ] Subprocess-based `Stage4Vibecoding.kt` / `Stage4Fixed.kt` as SDK fallback
- [ ] `Stage4Live.kt` for continuous emotion pipeline demo
- [ ] Recorded video of the full Stage 4 fix path in case hardware fails live
