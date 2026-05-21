import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.runBlocking

/** Sanity-check that Koog is wired up correctly before building Stage 4. */
fun main() = runBlocking {
    loadDotEnv()
    val apiKey = envOrFail("ANTHROPIC_API_KEY")

    val executor = PromptExecutor.builder()
        .anthropic(apiKey)
        .build()

    val strategy = functionalStrategy<String, String> { input ->
        val response = requestLLM(input)
        response.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
    }

    val agent = AIAgent(
        promptExecutor = executor,
        llmModel = AnthropicModels.Sonnet_4,
        strategy = strategy,
        systemPrompt = "You are a terse Kotlin assistant. Reply in one sentence."
    )
    val result = agent.run("Confirm Koog is wired up. Return just the word OK plus the Kotlin version you'd recommend for Stage 4.")
    println("agent reply: $result")
}
