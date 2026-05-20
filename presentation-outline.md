# RoboCoders: Judgment Day — AI Coding Agents Face Off (Kotlin Edition)

**Spec:** Mode f (parallel competitive live coding) | 45 min | KotlinConf | Kotlin developers who use AI coding tools daily
**Slide budget:** ~10 slides (0.22 slides/min — mode f default, minimal slide reliance)
**Pacing target:** 150-170 WPM, 80% time in IDE/camera feed, 20% slides
**Co-presenter:** Viktor Gamov (@gamussa). Equal airtime.
**Agents:** Baruch → Claude Code (Opus). Viktor → Junie (JetBrains) running Gemini Flash 3.5.
**Language:** Everything in Kotlin/JVM. Demo stack: DJL (Deep Java Library) + JavaCV + Ktor + coroutines + Gradle.

---

## Hardware Stack (on stage)

- Shelly Duo GU10 RGBW bulb → identity signal (local HTTP REST, ~50ms)
- DJI Osmo Pocket 3 → camera (1" sensor, 1080p/60fps USB-C webcam mode)
- Govee H6056 Light Bar #1 → confidence meter (vertical, 6 physical segments, red→green)
- Govee H6056 Light Bar #2 → emotion signal (color-coded per emotion)

## Software Stack (what the agents emit)

- **Language:** Kotlin 2.x on JVM 21
- **Camera:** JavaCV (`org.bytedeco:javacv-platform`) — OpenCV bindings for frame capture
- **Vision:** DJL with PyTorch engine — RetinaFace (detect), FaceNet/ArcFace (embed), ViT (emotion)
- **IoT:** Ktor client (CIO engine) + kotlinx.serialization for Shelly + Govee REST
- **Concurrency:** Kotlin coroutines, `Flow<Frame>` for the pipeline
- **Build:** Gradle Kotlin DSL, single multi-module project (`live/`, `ready/`)

---

## Opening Sequence [3 min, slides 1-4]
*(Tightened from 4 min — Stage 0 now carries the "context engineering first impression" weight, opening can be punchier.)*

### Slide 1: Title Slide
- Visual: Terminal dark background. Title: "RoboCoders: Judgment Day" in green monospace. Subtitle: "AI Coding Agents Face Off — Kotlin Edition"
- Footer: `@jbaruch  @gamussa  #KotlinConf  #RoboCoders  speaking.jbaru.ch`
- Speaker: [no notes — title slide held during opening chaos]

### Slide 2: Die Hard "Agent Johnson" cold open
- Visual: FULL — the iconic Die Hard screenshot of the two Agent Johnsons ("no relation")
- Speaker: [BARUCH]: "Agent Johnson. And Special Agent Johnson. No relation." *(beat)* "That's the last time two Agent Johnsons in one room was funny. Today we're putting a lot of agents in this room. And they're ALL going to claim they're the real one."
- [CALLBACK: reappears every time an AI misidentifies a face — running gag]
- [VIKTOR]: "I'm Viktor. He's Baruch. Definitely no relation. Both of us came here armed. He's got Claude Code on Opus. I've got Junie running Gemini Flash 3.5. And by the end of 45 minutes one of these agents will have written Kotlin that watches your face, reads your emotions, and turns the lights on. Maybe."

### Slide 3: Brief Bio + Shownotes (combined for time)
- Visual: Two terminal columns. Left: "$ whoami → Baruch Sadogursky. Context Sommelier. Tessl." Right: "$ whoami → Viktor Gamov. Java Champion. Vibes dealer." Center-bottom: QR + `speaking.jbaru.ch/robocoders-kotlinconf`
- Speaker: [BARUCH]: "I work at Tessl — disclosure done. Slides, code, plugins, everything — scan the QR. Nobody writes anything down."
- [VIKTOR]: "I write too much Java. Today I'm writing Kotlin. Mostly because Junie is writing it for me."
- [ANTI-SELL PATTERN]: explicit Tessl mention early, disarms the commercial-intent objection

### Slide 4: Side-picking vote + rules
- Visual: Split screen. Left: Claude Code logo + "Team Baruch". Right: Junie logo + "Team Viktor". Center: "Pick a side. Wrong side: t-shirt anyway."
- Speaker: [BARUCH]: "Five stages. Same prompts. Same hardware. Different agents. Vote now — Claude Code on Opus, or Junie on Gemini Flash 3.5? Hands. *(counts)* OK. You can change your mind at the end. There are t-shirts."
- [VIKTOR]: "I always bring t-shirts. Only consistent thing about me."
- [BARUCH]: "Today we teach our agents to identify which Agent Johnson is which. Camera, smart bulb, two light bars. In Kotlin. *(beat)* You'd think the 'in Kotlin' part would be the easy bit."

---

## Stage 0: Opening Salvo — Pick a Language [3 min]

### [DEMO 00]: "Turn on my Shelly Duo GU10"
- Devices: Shelly bulb only
- Prompt (both agents): *"Write a program that turns on my Shelly Duo GU10 smart bulb. The bulb is on the LAN; its IP is in the SHELLY_BULB_IP environment variable."*
- Without any plugins, both agents emit **Python with `requests`**, or a Node snippet, or `curl`. The Kotlin Gradle project around them doesn't move the model's hand. Speaker holds the IDE up: "Notice the file. `.py`."
- Speaker: [BARUCH]: "I asked it in a Kotlin project. It gave me Python. We haven't told it anything about us. Watch."
- [LIVE INSTALL BEAT]: `tessl install jbaruch/kotlin-tutor` — pull up `https://tessl.io/registry/jbaruch/kotlin-tutor` on second monitor. 12 always-apply rules.
- Re-issue the SAME prompt. Both agents now emit `build.gradle.kts` + Kotlin + Ktor client + JVM shutdown hook + the bulb actually responds.
- Speaker: [BARUCH]: "Same prompt. Different language. Different libraries. Different file extension. One `tessl install` changed everything. **We haven't even started the camera yet.**"
- [VIKTOR]: "And that's just the language. Wait until we hit hardware."

---

## Stage 1: Face Detection on Camera → Bulb [4 min]

### Slide 5: Stage 01 title card
- Visual: Terminal header "Stage 01: Face in frame → bulb stays on."
- Speaker: [BARUCH]: "Stage one. We have a working bulb-toggler. Now I want it to react to a face."

### [DEMO 01]: Face detection → bulb on/off
- Devices: DJI Osmo Pocket 3 + Shelly bulb
- Prompt (both agents): *"Extend the program so it only turns the bulb on while a face is in the camera, and off when no face is in frame."* — no mention of Kotlin, JavaCV, or Ktor. The `kotlin-tutor` install from Stage 0 already steered everything.
- Both agents extend the Stage-0 Kotlin code with `OpenCVFrameGrabber` (JavaCV) + Haar cascade. Bulb on/off works. But the FLICKER is visible.
- [STAGE DIRECTION: Baruch steps to frame edge for 5 seconds. Bulb strobes on/off.]
- Preview window shows face detection boxes so audience sees what the camera sees.
- Speaker: [BARUCH]: "See the flicker? Neither of us debounced detection intermittency. Both demos work, neither is robust. Remember this — pays off in 8 minutes."
- [FORESHADOWING PATTERN]

---

## Stage 2: Face Recognition → Identity Color [4 min]
*(Compressed — single "still a tie" beat, no extended setup)*

### Slide 6: Stage 02 title card
- Visual: Terminal header "Stage 02: Who is this? Baruch → blue. Viktor → red. Both → purple. Unknown → white."
- Speaker: [BARUCH]: "Stage two. Now the bulb knows WHO."
- [VIKTOR]: "Agent Johnson. And Special Agent Johnson. No relation." [CALLBACK]

### [DEMO 02]: Identity color mapping
- Devices: Camera + Shelly bulb
- Prompt (both agents): *"Extend the program so the bulb colour follows who is in frame. Baruch → blue. Viktor → red. Both → purple. Unknown → white. No one → off. Enrol from `faces/baruch/*.jpg` and `faces/viktor/*.jpg`."* — note: no mention of DJL or FaceNet. The `djl-for-jvm-ml` rule from `kotlin-tutor` already steers the agent toward DJL.
- Both agents succeed — DJL FaceNet, cosine distance against pre-enrolled embeddings (`faces/enrolled.bin`).
- Preview window shows face boxes with identity labels.
- Speaker: [BARUCH]: "Bulb follows identity. Still a tie."
- [VIKTOR]: "Told you we could go home."
- [BARUCH]: "Watch stage 3. Wheels come off."
- [INVERSE MR. FUSION JOKE]: "Back to the Future: Mr. Fusion takes trash and makes energy. Modern AI is the opposite — you give it energy, tokens, compute — and it makes trash. Unless you tell it what 'not trash' looks like."

---

## Stage 3: Confidence Semaphore — THE FIRST AHA [12 min]

### Slide 7: Stage 03 title card
- Visual: Terminal header "Stage 03: How sure is the machine?". ASCII semaphore: bottom RED always on, mid YELLOW on medium, top GREEN on strong.
- Speaker: [BARUCH]: "Bulb says WHO. How confident? Bottom red = system on. Middle yellow = maybe. Top green = sure. One Govee bar."

### [DEMO 03 vibecoding]: Semaphore without plugins — THE DESIGNED FAILURE
- Devices: Camera + Govee Bar #1 (Yankee, 6 physical segments)
- Prompt (both agents): *"Show recognition confidence on a Govee H6056 bar as a 3-level semaphore: bottom cells always red, middle yellow when medium, top green when good."*
- FOUR context misses hit simultaneously — **all language-agnostic, all survive the Kotlin port**:
  1. **Phantom segments** — Govee API accepts 15, hardware has 12. Segments 12-14 return 200, nothing lights.
  2. **Bar split** — agent treats 15 as one bar; "middle yellow" spans BOTH physical bars (Yankee + Golf).
  3. **Textbook confidence formula** — `1.0 - distance / tolerance` makes a strong FaceNet match (d≈0.45) look like 40%. Green never triggers. *(Note: FaceNet distances differ from dlib — calibration constants updated, miss is the same.)*
  4. **Top-down inversion** — "bottom red" ends up at the TOP because segment 0 is at the top of the bar.
- Speaker: [BARUCH]: "Viktor, count the segments." [VIKTOR]: *counts* "...12." [BARUCH]: "I commanded 15. API says 15. Govee said 200 OK to 15. Reality says 12. This is what 'confident but wrong' looks like."
- [BARUCH]: "Red? I said bottom. It's at the TOP. Yellow? Spilling into the other bar. Green? Never shows up — formula says 40% confident when my face is THIS close. Four bugs, zero errors, zero warnings. Every HTTP call returned 200."

### Slide 8: The diagnosis — "Silent semantic failures"
- Visual: Side-by-side confidence table for FaceNet (the Kotlin/DJL pipeline):
  ```
  cosine_d | vibecoding | plugin | Yankee bar
  d=0.35   |  0.42  |  1.00  | [██████] GREEN
  d=0.45   |  0.25  |  0.83  | [·█████]
  d=0.55   |  0.08  |  0.50  | [···███]
  d=0.65   |  0.00  |  0.17  | [·····█]
  ```
- Speaker: [BARUCH]: "Same raw cosine distance. Two completely different stories. The textbook formula is technically correct and demo-wrong. The calibration is empirical — you have to measure on YOUR model and YOUR hardware."

### [DEMO 03 fix]: Install plugins, re-prompt the SAME
```bash
tessl install jbaruch/govee-h6056
tessl install jbaruch/face-recognition-calibration-djl
tessl install jbaruch/iot-actuator-patterns-kotlin
tessl install jbaruch/vision-pipeline-foundations-kotlin
```
- Same prompt. Same agent. Different CONTEXT.
- Result: semaphore correct — RED bottom (system on), YELLOW mid (medium), GREEN top (strong). Only Yankee lights, Golf stays dark. 20+ fps. No flicker (Stage 1 foreshadowing paid off by debounce + persistence in `iot-actuator-patterns-kotlin`).
- Speaker: [BARUCH]: "Same agent. Same prompt. Four bugs gone. None of them would have been caught by a code reviewer. All four encoded in versioned, installable, shareable plugins I didn't write by hand."
- [LIVE INSTALL BEAT]: Show `tessl list` on screen — real registry versions.
- Open `https://tessl.io/registry/jbaruch/govee-h6056` — "not hypothetical."

---

## Stage 4: Emotion via Sub-Agents — THE SECOND AHA [12 min]

### Slide 9: Stage 04 title card
- Visual: Terminal header "Stage 04: Delegation Day". Below: diagram showing parent agent → 3 sub-agents, each with "fresh context ⚠️".
- Speaker: [BARUCH]: "Last stage. Emotion on the second bar. Decompose: vision sub-agent, IoT sub-agent, eval sub-agent."
- [VIKTOR]: "Sounds like microservices. What could go wrong."

### [DEMO 04 vibecoding]: Sub-agents WITHOUT plugin handoff — EVERYTHING BREAKS
- Devices: All four — Camera + Shelly bulb + Govee Bar #1 (Yankee) + Govee Bar #2 (Golf)
- Prompt: *"Decompose Stage 3 into sub-agents. Add emotion classification on the Golf bar via DJL ViT: happy=yellow, sad=blue, angry=red, neutral=gray, surprise=cyan, fear=purple, disgust=green."*
- Sub-agents spawn WITHOUT the Stage-3 plugins. Each reinvents reality:
  - **vision sub-agent**: textbook confidence formula returns (conf=0.25 instead of 0.83 for d=0.45)
  - **iot sub-agent**: addresses segments 0-14, splits bars wrong — "emotion on Golf" COLLAPSES because the sub-agent has no idea what Golf IS
  - **eval sub-agent**: reports STROBING — no stability filter
- Speaker: [BARUCH]: *(staring at chaos)* "We spent the last stage getting this right. I wrote the plugins. The parent agent READ the plugins. Then it spawned sub-agents. And every sub-agent is doing the 2024 version of this code."
- [VIKTOR]: "So plugins don't help you?"
- [BARUCH]: "Plugins help the AGENT THAT KNOWS ABOUT PLUGINS. Spawn a sub-agent without explicitly handing it the plugins, it's got nothing. Every delegation is a regression. Unless —"

### [DEMO 04 fix]: Install `sub-agent-delegation` meta-plugin
```bash
tessl install jbaruch/sub-agent-delegation
```
- Meta-plugin teaches the orchestrator: sub-agents start FRESH. Skills don't inherit. Pass them explicitly via `AgentDefinition(skills=[...])` + echo-skills validation handshake.
- Re-run: sub-agents echo their skills, parent validates, pipeline works.
- **LIVE CONTINUOUS DEMO**: `Stage4Live.kt` — Yankee semaphore + Golf emotion simultaneously. Smile → yellow Golf. Frown → red Golf. Surprise → cyan Golf. Audience watches both bars respond in real-time.
- Preview window shows face boxes with identity + emotion labels.
- [CALLBACK]: Point camera at audience briefly — "Room is 60% confused, 20% neutral. Checks out."
- Speaker: [BARUCH]: "Three agents. Shared plugins. EXPLICITLY passed through a meta-plugin."
- [VIKTOR]: "Context engineering for the orchestrator itself."
- [BARUCH]: "Last year's RoboCoders was about WHICH tool. This year's is about what the tools share."

---

## Close [7 min]

### Slide 10: "From vibecoding to context engineering" — CRYSTALLIZATION
- Visual: Two-column terminal comparison.
  - Left column: `2023-2025: VIBECODING` — "Prompt → hope | Stack Overflow | Single agent | 'It works on my machine' | Silent semantic failures"
  - Right column: `2026+: CONTEXT ENGINEERING` — "Plugins → agents → verify | Versioned, shared plugins | Orchestrated sub-agents | 'It works with my plugins' | Known empirical calibrations"
- Speaker: [BARUCH]: "This is the shift. Not which agent is smartest. It's the engineering discipline around the agent. Vibecoding is what we did when AI was new. Context engineering is what we do when AI is a load-bearing dependency — same way you'd never let an untyped scripting language into a 100k-line Kotlin codebase without a reason."

### Slide 11: Three Monday actions + thanks
- Visual: Three terminal bullets + closing handles + QR.
  - `$ tessl tile new` — package one team Kotlin convention as a plugin
  - `$ replace one ad-hoc prompt with a versioned spec`
  - `$ tessl eval run` — our govee-h6056 plugin: 27% baseline → 100% with context. That's the number.
- Speaker: [BARUCH]: "Three things Monday morning. One: take one Kotlin convention your team explains in code review every week — coroutine scope, error handling pattern, whatever — and package it as a plugin. Two: pick one recurring AI prompt and turn it into a reusable, versioned spec. Three: stop evaluating agents by gut. Write one eval. Our govee plugin scored 27% without context, 100% with."
- [VIKTOR]: "And if you do none of these, fine. Just stop calling it 'AI engineering' and call it 'vibecoding' honestly."
- [BARUCH]: "Closing vote — anyone change their mind on Claude Code vs Junie? *(counts)* Interesting. *(beat)* But you all changed your mind on something bigger, right? If you thought the agent was the variable — it isn't. The context is."
- [VIKTOR]: "T-shirts at the door. Questions wherever we're standing after. Thanks, KotlinConf."
- [CALLBACK close]: [BARUCH]: "Agent Johnson. Special Agent Johnson. No relation. But context? Everything."

---

## Cut lines / risk register

- **If Stage 3 vibecoding finishes early**: linger on the confidence table — don't fill time with code review.
- **If Stage 4 sub-agent handoff misfires live**: fall back to recorded run-output JSON side-by-side. `Stage4Live.kt` is the showpiece; `Stage4Vibecoding.kt` / `Stage4Fixed.kt` are the safe path.
- **If DJL model download hangs in the venue Wi-Fi**: pre-warm `~/.djl.ai/cache/` on every dev machine + offline jar with bundled weights.
- **If Junie/Gemini Flash 3.5 produces dramatically different Kotlin than expected**: that's content, not failure. Use it. ("Look — same prompt, two completely different solutions, neither correct without plugins.")

## Pre-flight checklist (presentation-level — full hardware/software list in DEMO-SCRIPT.md)

- [ ] Both laptops have JDK 21, Gradle wrapper warmed, DJL native libs cached
- [ ] `faces/enrolled.bin` (FaceNet embeddings, pre-computed) on both machines
- [ ] Govee bars paired, Shelly bulb at static IP, travel router on
- [ ] **No Tessl artifacts at project root** on either laptop (`ls AGENTS.md CLAUDE.md .mcp.json .tessl` returns nothing, `tessl.json` has `dependencies: {}`). The live `tessl install` beat in Stage 3 must create these from scratch so the audience sees real install activity.
- [ ] T-shirts in the room
- [ ] Die Hard "Agent Johnson" image licensed/sourced

## Plugin inventory (Kotlin edition)

Device-truth plugins (language-agnostic, cleaned up from Python references):
- `jbaruch/govee-h6056` — segment topology, bar mapping, phantom-segment caveat
- `jbaruch/shelly-duo-gu10` *(new)* — mDNS discovery name, REST endpoints, color model

Calibration plugins (empirical, re-measured for the DJL pipeline):
- `jbaruch/face-recognition-calibration-djl` *(new — replaces face-recognition-calibration)* — FaceNet cosine distance bands

Code-pattern plugins (Kotlin variants of the Python originals):
- `jbaruch/iot-actuator-patterns-kotlin` — debounce/quantization/progress-bar in Kotlin coroutines + Flow
- `jbaruch/vision-pipeline-foundations-kotlin` — JavaCV camera setup + frame-skip via `Flow.sample()`

Meta-plugin (unchanged, language-agnostic):
- `jbaruch/sub-agent-delegation` — explicit handoff + echo validation

---

## The narrative arc in one sentence

**"Both agents can write Kotlin that runs. Neither can write Kotlin that is RIGHT — until you engineer the context they both inherit. And when you delegate, you have to engineer the context PROPAGATION too — because by default, sub-agents get nothing."**
