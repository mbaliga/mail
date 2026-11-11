package dev.aarso.mail.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import java.util.concurrent.ConcurrentHashMap

/** A model's answer, with where it was computed — shown with Hyle's provenance glyph, never by colour alone. */
data class AiResult<T>(val value: T, val engineId: String, val engineLabel: String, val onDevice: Boolean)

/**
 * The router: for each task, which engines may run it and in what order.
 *
 * On-device first by default — Gemini Nano through AICore, then a local Gemma file — because the
 * person asked for intelligence on the device wherever possible. The BYOK cloud engine is consulted
 * only for tasks the person allowed, never for mail in a protected category or account, only while
 * the daily budget lasts, and with identifiers redacted. If every engine fails or none is allowed,
 * the caller keeps its heuristic answer: a model is an upgrade, never a dependency.
 */
class Intelligence(
    private val engines: () -> List<LlmEngine>,
    private val settings: () -> AiSettings,
    private val ledger: UsageLedger = UsageLedger(),
) {
    private data class Probe(val availability: Availability, val at: Long)
    private val probes = ConcurrentHashMap<String, Probe>()

    private suspend fun ready(e: LlmEngine): Boolean {
        val cached = probes[e.id]
        val now = System.currentTimeMillis()
        val a = if (cached != null && now - cached.at < 60_000) cached.availability else e.availability().also { probes[e.id] = Probe(it, now) }
        return a is Availability.Ready
    }

    fun invalidate() = probes.clear()

    /** Engines that may run [task] on mail of [sensitivity], in the order they should be tried. */
    suspend fun plan(task: AiTask, sensitivity: Sensitivity = Sensitivity.NONE): List<LlmEngine> {
        val s = settings()
        val all = engines()
        val local = if (s.onDeviceEnabled) all.filter { it.onDevice && ready(it) } else emptyList()
        val cloudAllowed = s.cloudEngineId != null &&
            task in s.cloudTasks &&
            (sensitivity.category == null || sensitivity.category !in s.neverCloudCategories) &&
            (sensitivity.accountId == null || sensitivity.accountId !in s.neverCloudAccounts) &&
            (s.dailyCloudTokens == 0L || ledger.used() < s.dailyCloudTokens)
        val cloud = if (cloudAllowed) all.filter { !it.onDevice && it.id == s.cloudEngineId && ready(it) } else emptyList()
        return if (s.preferOnDevice) local + cloud else cloud + local
    }

    /** Whether *any* engine could run [task] right now — for showing or hiding AI affordances. */
    suspend fun canRun(task: AiTask, sensitivity: Sensitivity = Sensitivity.NONE): Boolean = plan(task, sensitivity).isNotEmpty()

    /**
     * Try each planned engine until one produces a value [parse] accepts. [build] receives the engine
     * (to size the input to its context) and a [Redactor] when the engine is off-device and redaction
     * is on; [parse] receives the same redactor to restore placeholders.
     */
    suspend fun <T> run(
        task: AiTask,
        sensitivity: Sensitivity,
        build: (LlmEngine, Redactor?) -> LlmRequest,
        parse: (String, Redactor?) -> T?,
    ): AiResult<T>? {
        for (engine in plan(task, sensitivity)) {
            val redactor = if (!engine.onDevice && settings().redactBeforeCloud) Redactor() else null
            val response = try {
                engine.generate(build(engine, redactor))
            } catch (e: EngineException) {
                if (!e.retryable) probes.remove(engine.id)
                continue
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (!engine.onDevice) ledger.record(response.inputTokens + response.outputTokens)
            val value = runCatching { parse(response.text, redactor) }.getOrNull() ?: continue
            return AiResult(value, engine.id, engine.label, engine.onDevice)
        }
        return null
    }

    /**
     * Streaming for compose-assist: the first planned engine streams; if it fails before producing
     * anything the next one is tried. [onEngine] reports which engine is writing, for provenance.
     */
    fun stream(
        task: AiTask,
        sensitivity: Sensitivity,
        build: (LlmEngine, Redactor?) -> LlmRequest,
        onEngine: (LlmEngine) -> Unit = {},
    ): Flow<String> = flow {
        for (engine in plan(task, sensitivity)) {
            val redactor = if (!engine.onDevice && settings().redactBeforeCloud) Redactor() else null
            var produced = false
            var failed = false
            onEngine(engine)
            engine.stream(build(engine, redactor))
                .catch { failed = true }
                .onCompletion { }
                .collect { chunk ->
                    produced = true
                    emit(redactor?.restore(chunk) ?: chunk)
                }
            if (produced || !failed) return@flow
        }
    }
}
