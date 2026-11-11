// MediaPipe's LLM Inference is deprecated in favour of LiteRT-LM, but LiteRT-LM needs Kotlin 2.3 /
// Java 21 metadata and the constellation pins Kotlin 2.1. See README → Intelligence.
@file:Suppress("DEPRECATION")

package dev.aarso.mail.ondevice

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import dev.aarso.mail.ai.Availability
import dev.aarso.mail.ai.EngineException
import dev.aarso.mail.ai.LlmEngine
import dev.aarso.mail.ai.LlmRequest
import dev.aarso.mail.ai.LlmResponse
import dev.aarso.mail.ai.LocalEngineFactory
import dev.aarso.mail.ai.trimToTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Found by name (see [LocalEngineFactory.IMPLEMENTATION]) once this module is present. */
class MediaPipeFactory : LocalEngineFactory {
    override fun create(context: Any, modelPath: () -> String?): LlmEngine = MediaPipeEngine(context as Context, modelPath)
}

/**
 * A Gemma (or any MediaPipe-compatible) model file on the phone. `.task` bundles carry their own
 * chat template; bare `.bin` weights get Gemma's. One inference at a time: the model holds
 * gigabytes, and a second copy would not fit.
 */
class MediaPipeEngine(private val context: Context, private val path: () -> String?) : LlmEngine, AutoCloseable {
    override val id = "local"
    override val label = "Local model on this phone"
    override val onDevice = true
    override val maxInputTokens = 3_000
    override val supportsStructuredOutput = false

    private val mutex = Mutex()
    private var llm: LlmInference? = null
    private var loaded: String? = null

    override suspend fun availability(): Availability {
        val p = path() ?: return Availability.Unavailable("No model file chosen")
        return if (File(p).canRead()) Availability.Ready else Availability.Unavailable("The model file is gone")
    }

    private fun engine(p: String): LlmInference {
        llm?.let { if (loaded == p) return it else it.close() }
        val options = LlmInference.LlmInferenceOptions.builder().setModelPath(p).setMaxTokens(4096).setMaxTopK(40).build()
        return LlmInference.createFromOptions(context, options).also { llm = it; loaded = p }
    }

    private fun text(r: LlmRequest, p: String): String {
        val json = if (r.json != null) "\n\nAnswer with JSON only, no other text." else ""
        val body = r.system + json + "\n\n" + trimToTokens(r.prompt, maxInputTokens)
        return if (p.endsWith(".bin", ignoreCase = true)) "<start_of_turn>user\n$body<end_of_turn>\n<start_of_turn>model\n" else body
    }

    override suspend fun generate(request: LlmRequest): LlmResponse = mutex.withLock {
        withContext(Dispatchers.Default) {
            val p = path() ?: throw EngineException("No local model", false)
            val inference = try { engine(p) } catch (e: Exception) { throw EngineException("Couldn't load the model: ${e.message}", false, e) }
            val session = LlmInferenceSession.createFromOptions(
                inference,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(40).setTemperature(0.3f).build(),
            )
            try {
                session.addQueryChunk(text(request, p))
                val out = session.generateResponse().replace("<end_of_turn>", "").trim()
                if (out.isBlank()) throw EngineException("The local model gave no answer", true)
                LlmResponse(out, id, true)
            } catch (e: EngineException) {
                throw e
            } catch (e: Exception) {
                throw EngineException("Local model: ${e.message}", true, e)
            } finally {
                session.close()
            }
        }
    }

    override fun close() {
        llm?.close(); llm = null; loaded = null
    }
}
