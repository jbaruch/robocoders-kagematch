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
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private fun anthropicExecutor(apiKey: String) =
    PromptExecutor.builder().anthropic(apiKey).build()

private val passthroughStrategy = functionalStrategy<String, String> { input ->
    val response = requestLLM(input)
    response.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
}

/**
 * Read a plugin's rules from the local Tessl install. The Fixed sub-agents inject this
 * content into their system prompt at agent-creation time, so the rules stay authoritative
 * on disk (in the installed plugin directory) — not copy-pasted into this file.
 *
 * Koog 1.0.0-preview3 does not have a native "skills" concept (see
 * https://github.com/JetBrains/koog/issues/1383). When it lands, this can be replaced
 * with a real skill registration call. Until then, file-read + system-prompt injection
 * is the fallback the sub-agent-delegation meta-plugin prescribes.
 */
private fun readSkillRules(pluginName: String): String {
    val tileDir = listOf(
        java.io.File(".tessl/tiles/jbaruch/$pluginName"),
        java.io.File("../../.tessl/tiles/jbaruch/$pluginName"),
        java.io.File("/Users/jbaruch/Projects/robocoders-kagematch/.tessl/tiles/jbaruch/$pluginName")
    ).firstOrNull { it.isDirectory }
        ?: error("Plugin not installed: jbaruch/$pluginName. Run `tessl install jbaruch/$pluginName` from the project root before invoking Stage 4 Fixed sub-agents.")
    val rulesDir = java.io.File(tileDir, "rules")
    return rulesDir.listFiles { f -> f.extension == "md" }
        ?.sortedBy { it.name }
        ?.joinToString("\n\n---\n\n") { it.readText() }
        ?: ""
}

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

private fun buildAgent(apiKey: String, systemPrompt: String): AIAgent<String, String> =
    AIAgent(
        promptExecutor = anthropicExecutor(apiKey),
        llmModel = AnthropicModels.Sonnet_4,
        strategy = passthroughStrategy,
        systemPrompt = systemPrompt
    )

private fun visionAgent(apiKey: String, systemPrompt: String) = buildAgent(apiKey, systemPrompt)
private fun iotAgent(apiKey: String, systemPrompt: String) = buildAgent(apiKey, systemPrompt)
private fun evalAgent(apiKey: String, systemPrompt: String) = buildAgent(apiKey, systemPrompt)

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

    SKILL HANDED OVER FROM PARENT (read from installed plugin jbaruch/face-recognition-calibration-djl):
    ${readSkillRules("face-recognition-calibration-djl")}

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

    SKILL HANDED OVER FROM PARENT (read from installed plugin jbaruch/govee-h6056):
    ${readSkillRules("govee-h6056")}

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

    SKILL HANDED OVER FROM PARENT (read from installed plugin jbaruch/iot-actuator-patterns-kotlin):
    ${readSkillRules("iot-actuator-patterns-kotlin")}

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
