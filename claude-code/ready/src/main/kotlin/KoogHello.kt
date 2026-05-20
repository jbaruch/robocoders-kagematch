import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import kotlinx.coroutines.runBlocking

/** Sanity-check that Koog is wired up correctly before building Stage 4. */
fun main() = runBlocking {
    loadDotEnv()
    val apiKey = envOrFail("ANTHROPIC_API_KEY")

    val agent = AIAgent(
        promptExecutor = simpleAnthropicExecutor(apiKey),
        llmModel = AnthropicModels.Sonnet_4,
        systemPrompt = "You are a terse Kotlin assistant. Reply in one sentence."
    )
    val result = agent.run("Confirm Koog is wired up. Return just the word OK plus the Kotlin version you'd recommend for Stage 4.")
    println("agent reply: $result")
}
