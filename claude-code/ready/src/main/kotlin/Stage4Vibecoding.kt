/**
 * Stage 4 VIBECODING — sub-agents spawned WITHOUT plugin context.
 *
 * Each sub-agent (vision, iot, eval) starts with a fresh LLM context. The parent
 * orchestrator did NOT pass any skills to them — vibecoding-style "just spawn the
 * sub-agents and trust them" decomposition.
 *
 * Without the plugins, the LLM falls back to whatever it remembers from training data:
 *   - vision: textbook  1 - d / tolerance  formula (compresses strong matches)
 *   - iot:    naive segment thirds across the API's 15 (sends to phantoms)
 *   - eval:   no stability filter (reports strobing on every frame change)
 *
 * Bars go haywire as a result. This is the broken half of the Stage 4 aha.
 */
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    loadDotEnv()
    val anthropicKey = envOrFail("ANTHROPIC_API_KEY")
    val camIndex = env("CAM")?.toIntOrNull() ?: 1
    val maxSeconds = env("MAX_SECONDS")?.toIntOrNull()

    s4log("[VIBECODING] Stage 4 — sub-agents with NO plugin handoff")
    s4log("Parent orchestrator: 'Decompose Stage 3 into sub-agents: vision, iot, eval.'")
    s4log("Skills passed to sub-agents: (none)")
    s4log("---")

    s4log("Spawning vision sub-agent (fresh context, no skills)…")
    val vision = invokeAgent<VisionDecision>(visionAgentVibecoding(anthropicKey), "Decide now.")
    s4log("  vision: method=${vision.method}  tolerance=${vision.tolerance}  strong=${vision.strongThreshold}  reject=${vision.rejectThreshold}")
    s4log("  rationale: ${vision.rationale}")

    s4log("Spawning iot sub-agent (fresh context, no skills)…")
    val iot = invokeAgent<IotDecision>(iotAgentVibecoding(anthropicKey), "Decide now.")
    s4log("  iot: totalSegments=${iot.totalSegments}  offRgb=${iot.offRgb}")
    iot.zones.forEach { z -> s4log("    ${z.name} ${z.zone}: ${z.segments}") }
    s4log("  rationale: ${iot.rationale}")

    s4log("Spawning eval sub-agent (fresh context, no skills)…")
    val eval = invokeAgent<EvalDecision>(evalAgentVibecoding(anthropicKey), "Decide now.")
    s4log("  eval: ${eval.stabilityMethod} (n=${eval.nTicks}, tick=${eval.tickMs}ms)")
    s4log("  rationale: ${eval.rationale}")

    s4log("---")
    s4log("Applying sub-agent decisions to runtime. Watch the bars.")
    runStage4(vision, iot, eval, title = "VIBECODING", camIndex = camIndex, maxSeconds = maxSeconds)
}
