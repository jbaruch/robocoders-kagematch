/**
 * Stage 0 rehearsal — same prompt with/without jbaruch/kotlin-tutor rules,
 * see whether the agent picks Python or Kotlin.
 */
import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import kotlinx.coroutines.runBlocking
import java.io.File

private const val PROMPT =
    "Write a program that turns on my Shelly Duo GU10 smart bulb. " +
    "The bulb is on the LAN; its IP is in the SHELLY_BULB_IP environment variable."

fun main() = runBlocking {
    loadDotEnv()
    val key = envOrFail("ANTHROPIC_API_KEY")

    // VIBECODING — no plugin context. Just a generic engineer system prompt.
    println("======================================================================")
    println("  PHASE A — VIBECODING (no jbaruch/kotlin-tutor)")
    println("======================================================================")
    val vibecoding = AIAgent(
        promptExecutor = simpleAnthropicExecutor(key),
        llmModel = AnthropicModels.Sonnet_4,
        systemPrompt = "You are a senior software engineer. Write production-ready code for the user's request. Pick the language and libraries you think best fit."
    )
    val vibecodingOut = vibecoding.run(PROMPT)
    println(vibecodingOut)
    println("\n[vibecoding] length=${vibecodingOut.length}  contains-python=${"python" in vibecodingOut.lowercase() || "import requests" in vibecodingOut.lowercase() || "```py" in vibecodingOut.lowercase() || "```python" in vibecodingOut.lowercase()}  contains-kotlin=${"kotlin" in vibecodingOut.lowercase() || "fun main" in vibecodingOut || "```kotlin" in vibecodingOut.lowercase()}")

    // FIXED — system prompt includes kotlin-tutor rules as the tessl-managed agent would see them.
    println("\n\n======================================================================")
    println("  PHASE B — FIXED (jbaruch/kotlin-tutor rules in system prompt)")
    println("======================================================================")
    val rulesDir = listOf(
        File(".tessl/tiles/jbaruch/kotlin-tutor/rules"),
        File("../../.tessl/tiles/jbaruch/kotlin-tutor/rules"),
        File("/Users/jbaruch/Projects/robocoders-kagematch/.tessl/tiles/jbaruch/kotlin-tutor/rules")
    ).firstOrNull { it.isDirectory }
        ?: error("kotlin-tutor rules not found. Did you run `tessl install jbaruch/kotlin-tutor` at the project root?")
    val rulesText = rulesDir.listFiles { f -> f.extension == "md" }
        ?.sortedBy { it.name }
        ?.joinToString("\n\n---\n\n") { it.readText() }
        ?: error("rulesDir found but listFiles returned null: ${rulesDir.absolutePath}")
    println("[fixed] loaded ${rulesText.length} chars of kotlin-tutor rules from ${rulesDir.absolutePath}")
    val fixed = AIAgent(
        promptExecutor = simpleAnthropicExecutor(key),
        llmModel = AnthropicModels.Sonnet_4,
        systemPrompt = """
            You are a senior software engineer. Write production-ready code for the user's request. Pick the language and libraries you think best fit.

            The following rules are loaded from your context plugins (jbaruch/kotlin-tutor). Follow them.

            $rulesText
        """.trimIndent()
    )
    val fixedOut = fixed.run(PROMPT)
    println(fixedOut)
    println("\n[fixed] length=${fixedOut.length}  contains-python=${"python" in fixedOut.lowercase() || "import requests" in fixedOut.lowercase() || "```py" in fixedOut.lowercase() || "```python" in fixedOut.lowercase()}  contains-kotlin=${"kotlin" in fixedOut.lowercase() || "fun main" in fixedOut || "```kotlin" in fixedOut.lowercase()}")
}
