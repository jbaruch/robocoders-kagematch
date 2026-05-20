/**
 * Stage 4 FIXED — sub-agents spawned with explicit plugin handoff.
 *
 * After installing the sub-agent-delegation meta-plugin, the parent orchestrator
 * EXPLICITLY passes the relevant skills to each sub-agent's system prompt:
 *   - vision sub-agent ← face-recognition-calibration-djl
 *   - iot sub-agent    ← govee-h6056
 *   - eval sub-agent   ← iot-actuator-patterns-kotlin (debounce-controller)
 *
 * The sub-agents now have the same domain knowledge the parent does, and their
 * decisions match Stage 3 Fixed: piecewise confidence, Yankee-only bottom-up
 * semaphore, Golf for emotion, 2-tick stability filter.
 *
 * Bars work correctly. This is the fixed half of the Stage 4 aha.
 */
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    loadDotEnv()
    val anthropicKey = envOrFail("ANTHROPIC_API_KEY")
    val camIndex = env("CAM")?.toIntOrNull() ?: 1
    val maxSeconds = env("MAX_SECONDS")?.toIntOrNull()

    s4log("[FIXED] Stage 4 — sub-agents with EXPLICIT plugin handoff (sub-agent-delegation meta-plugin)")
    s4log("Parent orchestrator: 'Decompose Stage 3 into sub-agents: vision, iot, eval.'")
    s4log("Skills passed to sub-agents:")
    s4log("  vision ← face-recognition-calibration-djl")
    s4log("  iot    ← govee-h6056")
    s4log("  eval   ← iot-actuator-patterns-kotlin / debounce-controller")
    s4log("---")

    s4log("Spawning vision sub-agent (with face-recognition-calibration-djl skill)…")
    val vision = invokeAgent<VisionDecision>(visionAgentFixed(anthropicKey), "Decide now.")
    s4log("  vision: method=${vision.method}  tolerance=${vision.tolerance}  strong=${vision.strongThreshold}  reject=${vision.rejectThreshold}")
    s4log("  rationale: ${vision.rationale}")

    s4log("Spawning iot sub-agent (with govee-h6056 skill)…")
    val iot = invokeAgent<IotDecision>(iotAgentFixed(anthropicKey), "Decide now.")
    s4log("  iot: totalSegments=${iot.totalSegments}  offRgb=${iot.offRgb}")
    iot.zones.forEach { z -> s4log("    ${z.name} ${z.zone}: ${z.segments}") }
    s4log("  rationale: ${iot.rationale}")

    s4log("Spawning eval sub-agent (with iot-actuator-patterns-kotlin skill)…")
    val eval = invokeAgent<EvalDecision>(evalAgentFixed(anthropicKey), "Decide now.")
    s4log("  eval: ${eval.stabilityMethod} (n=${eval.nTicks}, tick=${eval.tickMs}ms)")
    s4log("  rationale: ${eval.rationale}")

    s4log("---")
    s4log("Applying sub-agent decisions to runtime. Watch the bars.")
    runStage4(vision, iot, eval, title = "FIXED", camIndex = camIndex, maxSeconds = maxSeconds)
}
