/**
 * Stage 4 sub-agent definitions.
 *
 * Three sub-agents (vision, iot, eval) — each a Koog AIAgent with a fresh context.
 * Each variant comes in two flavors:
 *   - vibecoding: system prompt contains NO plugin context. The LLM falls back to
 *     textbook patterns from its training data.
 *   - fixed: system prompt inlines the relevant plugin rules. The LLM follows them.
 *
 * Each sub-agent returns a STRUCTURED JSON decision. The orchestrator applies it
 * deterministically — no arbitrary code is evaluated.
 */
import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- Structured outputs ---

@Serializable
data class VisionDecision(
    val method: String,             // "linear" or "piecewise"
    val tolerance: Float = 0.6f,    // for linear: conf = clip(0, 1 - d/tolerance)
    val strongThreshold: Float = 0.30f,  // for piecewise: d <= this → conf = 1.0
    val rejectThreshold: Float = 0.65f,  // for piecewise: d >= this → conf = 0.0
    val rationale: String
)

@Serializable
data class IotZone(val name: String, val zone: String, val segments: List<Int>)

@Serializable
data class IotDecision(
    val totalSegments: Int,                    // what the sub-agent thinks exist
    val zones: List<IotZone>,                  // BOTTOM/MID/TOP + EMOTION
    val offRgb: List<Int> = listOf(0, 0, 0),   // (0,0,0) is the buggy default
    val rationale: String
)

@Serializable
data class EvalDecision(
    val stabilityMethod: String,    // "none" or "n_tick"
    val nTicks: Int = 0,
    val tickMs: Int = 0,
    val rationale: String
)

val sharedJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

// --- Common scaffolding ---

private const val JSON_INSTRUCTION = """
Output ONLY a single JSON object. No prose before or after.
No markdown code fences. No "Here is the JSON:" preamble. Just the raw JSON.
"""

private fun visionAgent(apiKey: String, systemPrompt: String): AIAgent<String, String> =
    AIAgent(
        promptExecutor = simpleAnthropicExecutor(apiKey),
        llmModel = AnthropicModels.Sonnet_4,
        systemPrompt = systemPrompt
    )

private fun iotAgent(apiKey: String, systemPrompt: String): AIAgent<String, String> =
    AIAgent(
        promptExecutor = simpleAnthropicExecutor(apiKey),
        llmModel = AnthropicModels.Sonnet_4,
        systemPrompt = systemPrompt
    )

private fun evalAgent(apiKey: String, systemPrompt: String): AIAgent<String, String> =
    AIAgent(
        promptExecutor = simpleAnthropicExecutor(apiKey),
        llmModel = AnthropicModels.Sonnet_4,
        systemPrompt = systemPrompt
    )

// --- VIBECODING variants (no plugin context) ---

fun visionAgentVibecoding(apiKey: String) = visionAgent(
    apiKey,
    """
    You are the vision sub-agent in a face-recognition pipeline. The parent agent has
    decomposed the problem and is asking YOU to decide on the confidence mapping.
    You have no other context. Use what you remember from coding tutorials.

    Decide how to map a face-recognition distance d (a float, lower = closer match) to a
    confidence score in [0, 1].

    Output the decision as JSON with this schema:
    {
      "method": "linear" | "piecewise",
      "tolerance": <float>,
      "strongThreshold": <float>,
      "rejectThreshold": <float>,
      "rationale": "<one sentence explaining your choice>"
    }

    $JSON_INSTRUCTION
    """.trimIndent()
)

fun iotAgentVibecoding(apiKey: String) = iotAgent(
    apiKey,
    """
    You are the IoT sub-agent driving Govee H6056 light bars. The parent agent has
    decomposed the problem and is asking YOU to decide on the segment layout for a
    3-level (RED/YELLOW/GREEN) semaphore plus an emotion display.

    What you know:
    - The Govee H6056 API documents segments min=1, max=15.
    - The device is one logical device with multiple bars.
    - You don't have specific physical-mapping documentation.

    Decide the segment-to-zone mapping. Output JSON:
    {
      "totalSegments": <int>,
      "zones": [
        {"name": "<bar name or unknown>", "zone": "BOTTOM", "segments": [<int>...]},
        {"name": "...", "zone": "MID", "segments": [<int>...]},
        {"name": "...", "zone": "TOP", "segments": [<int>...]},
        {"name": "...", "zone": "EMOTION", "segments": [<int>...]}
      ],
      "offRgb": [<r>, <g>, <b>],
      "rationale": "<one sentence>"
    }

    $JSON_INSTRUCTION
    """.trimIndent()
)

fun evalAgentVibecoding(apiKey: String) = evalAgent(
    apiKey,
    """
    You are the evaluation sub-agent. The parent agent has decomposed the problem and
    is asking YOU how to decide whether a level signal (Int 0/1/2 emitted ~10 Hz from a
    face-recognition pipeline) is "strobing" or "stable" before driving an actuator.

    Decide a stability filter. Output JSON:
    {
      "stabilityMethod": "none" | "n_tick",
      "nTicks": <int>,
      "tickMs": <int>,
      "rationale": "<one sentence>"
    }

    $JSON_INSTRUCTION
    """.trimIndent()
)

// --- FIXED variants (system prompt inlines the relevant plugin rules — meta-plugin handoff) ---

fun visionAgentFixed(apiKey: String) = visionAgent(
    apiKey,
    """
    You are the vision sub-agent in a face-recognition pipeline.

    SKILL HANDED OVER FROM PARENT (face-recognition-calibration-djl):
      For DJL face_feature embeddings (ArcFace-derived, 512-d, cosine distance),
      use a PIECEWISE mapping calibrated empirically:
        d ≤ 0.30 → confidence = 1.0   (strong)
        d ≥ 0.65 → confidence = 0.0   (reject)
        else      → confidence = (0.65 - d) / 0.35
      AVOID the textbook formula  conf = 1 - d / tolerance — it compresses strong
      matches into the mid band (at d=0.30 it returns 0.50, which is "yellow" in any
      3-band semaphore).

    Decide the mapping (you have the skill — apply it). Output JSON:
    {
      "method": "linear" | "piecewise",
      "tolerance": <float>,
      "strongThreshold": <float>,
      "rejectThreshold": <float>,
      "rationale": "<one sentence>"
    }

    $JSON_INSTRUCTION
    """.trimIndent()
)

fun iotAgentFixed(apiKey: String) = iotAgent(
    apiKey,
    """
    You are the IoT sub-agent driving Govee H6056 light bars.

    SKILL HANDED OVER FROM PARENT (govee-h6056):
      - Govee H6056 advertises 15 segments. PHYSICAL TRUTH: only 12 exist (indices 0..11).
        Indices 12, 13, 14 are phantom — API returns 200 OK but no light.
      - Two physical bars share the address space:
          Yankee bar: segments 0..5
          Golf bar:   segments 6..11
      - Within each bar, segment[0] is at the TOP. Bottom-up fill = high→low index.
      - rgb=(0,0,0) is unreliable as "off" on this firmware (some paths treat 0x000000
        as no-op and silently retain prior state). Use rgb=(1,1,1) for off.

    Decide the segment-to-zone mapping for a 3-level (RED/YELLOW/GREEN) semaphore on
    Yankee and an emotion display on Golf. Output JSON:
    {
      "totalSegments": <int>,
      "zones": [
        {"name": "Yankee", "zone": "BOTTOM", "segments": [<int>...]},
        {"name": "Yankee", "zone": "MID", "segments": [<int>...]},
        {"name": "Yankee", "zone": "TOP", "segments": [<int>...]},
        {"name": "Golf",   "zone": "EMOTION", "segments": [<int>...]}
      ],
      "offRgb": [<r>, <g>, <b>],
      "rationale": "<one sentence>"
    }

    $JSON_INSTRUCTION
    """.trimIndent()
)

fun evalAgentFixed(apiKey: String) = evalAgent(
    apiKey,
    """
    You are the evaluation sub-agent.

    SKILL HANDED OVER FROM PARENT (iot-actuator-patterns-kotlin / debounce-controller):
      - For a real-time producer (camera loop at ~10 Hz) driving a rate-limited actuator,
        require a stability filter of 2 consecutive ticks before committing.
      - Tick duration 0.4 s.
      - Without this filter, a noisy producer that flickers between levels per-frame
        will cause the actuator to strobe and burn rate-limit budget.

    Decide a stability filter. Output JSON:
    {
      "stabilityMethod": "none" | "n_tick",
      "nTicks": <int>,
      "tickMs": <int>,
      "rationale": "<one sentence>"
    }

    $JSON_INSTRUCTION
    """.trimIndent()
)

// --- Helpers to invoke an agent and parse its JSON ---

suspend inline fun <reified T> invokeAgent(agent: AIAgent<String, String>, input: String): T {
    val raw = agent.run(input)
    // Trim possible markdown fences or extra prose around the JSON object
    val trimmed = raw.substringAfter('{').substringBeforeLast('}').let { "{$it}" }
    return sharedJson.decodeFromString<T>(trimmed)
}
