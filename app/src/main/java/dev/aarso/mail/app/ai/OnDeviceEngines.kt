// MediaPipe's LLM Inference is deprecated in favour of LiteRT-LM, but LiteRT-LM needs Kotlin 2.3 /
// Java 21 metadata and the constellation pins Kotlin 2.1. Until that pin moves, this is the
// supported way to run a Gemma file on phones without AICore. See README → Intelligence.
@file:Suppress("DEPRECATION")

package dev.aarso.mail.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.aarso.mail.ai.AiTask
import dev.aarso.mail.ai.Availability
import dev.aarso.mail.ai.EngineException
import dev.aarso.mail.ai.LlmEngine
import dev.aarso.mail.ai.LlmRequest
import dev.aarso.mail.ai.LlmResponse
import dev.aarso.mail.ai.trimToTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Gemini Nano through AICore — the model that ships with the phone, on the devices that have it.
 *
 * The general Prompt API answers every task (it is what gives the tl;dr its "who wants what" voice);
 * the purpose-built Summarization, Rewriting and Proofreading APIs are used where a request carries
 * their [LlmRequest.hints], and as a fallback on devices that expose only those features. Nothing
 * here leaves the device, so the router may hand it any message, including protected categories.
 */
class GeminiNanoEngine(private val context: Context) : LlmEngine {
    override val id = "nano"
    override val label = "Gemini Nano on this phone"
    override val onDevice = true
    // AICore's Prompt API accepts about 4,000 tokens in; leave room for the instructions.
    override val maxInputTokens = 3_000
    override val supportsStructuredOutput = false

    private val prompt: GenerativeModel by lazy { Generation.getClient() }

    private suspend fun promptStatus(): Int = try { prompt.checkStatus() } catch (_: Exception) { FeatureStatus.UNAVAILABLE }

    private suspend fun summarizerStatus(): Int = runCatching {
        val s = Summarization.getClient(SummarizerOptions.builder(context).build())
        try { s.checkFeatureStatus().await() } finally { s.close() }
    }.getOrDefault(FeatureStatus.UNAVAILABLE)

    override suspend fun availability(): Availability {
        val s = promptStatus()
        val effective = if (s == FeatureStatus.UNAVAILABLE) summarizerStatus() else s
        return when (effective) {
            FeatureStatus.AVAILABLE -> Availability.Ready
            FeatureStatus.DOWNLOADABLE -> Availability.Downloadable(null)
            FeatureStatus.DOWNLOADING -> Availability.Downloading(null)
            else -> Availability.Unavailable("This phone doesn't offer Gemini Nano (it needs AICore)")
        }
    }

    override suspend fun download(onProgress: (Float?) -> Unit) {
        var total = 0L
        prompt.download().collect { st ->
            when (st) {
                is DownloadStatus.DownloadStarted -> { total = st.bytesToDownload; onProgress(0f) }
                is DownloadStatus.DownloadProgress -> onProgress(if (total > 0) st.totalBytesDownloaded.toFloat() / total else null)
                is DownloadStatus.DownloadCompleted -> onProgress(1f)
                is DownloadStatus.DownloadFailed -> throw EngineException("Gemini Nano download failed: ${st.e.message}", true, st.e)
            }
        }
    }

    private fun combined(r: LlmRequest): String {
        val json = if (r.json != null) "\n\nAnswer with JSON only, no other text." else ""
        return r.system + json + "\n\n" + trimToTokens(r.prompt, maxInputTokens)
    }

    override suspend fun generate(request: LlmRequest): LlmResponse = withContext(Dispatchers.Default) {
        // Purpose-built paths first where the request asks for them; each falls through on failure.
        featureApi(request)?.let { return@withContext LlmResponse(it, id, true) }
        if (promptStatus() != FeatureStatus.AVAILABLE) {
            summaryFallback(request)?.let { return@withContext LlmResponse(it, id, true) }
            throw EngineException("Gemini Nano isn't ready", true)
        }
        val text = try {
            prompt.generateContent(
                generateContentRequest(TextPart(combined(request))) {
                    temperature = 0.2f
                    topK = 16
                    // AICore caps a single answer at 256 tokens.
                    maxOutputTokens = request.maxOutputTokens.coerceIn(16, 256)
                },
            ).candidates.firstOrNull()?.text.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            throw EngineException("Gemini Nano: ${e.message}", true, e)
        }
        if (text.isBlank()) throw EngineException("Gemini Nano gave no answer", true)
        LlmResponse(text.trim(), id, true)
    }

    override fun stream(request: LlmRequest): Flow<String> = flow {
        val rewritten = featureApi(request)
        if (rewritten != null) { emit(rewritten); return@flow }
        if (promptStatus() != FeatureStatus.AVAILABLE) throw EngineException("Gemini Nano isn't ready", true)
        prompt.generateContentStream(
            generateContentRequest(TextPart(combined(request))) {
                temperature = 0.4f
                maxOutputTokens = request.maxOutputTokens.coerceIn(16, 256)
            },
        ).mapNotNull { it.candidates.firstOrNull()?.text }.collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    /** Rewriting / proofreading through their dedicated APIs, which keep the person's voice better. */
    private suspend fun featureApi(r: LlmRequest): String? {
        if (r.task != AiTask.REWRITE) return null
        val input = r.hints["input"] ?: return null
        val style = r.hints["style"] ?: return null
        return runCatching {
            if (style == "PROOFREAD") {
                val p = Proofreading.getClient(ProofreaderOptions.builder(context).setInputType(ProofreaderOptions.InputType.KEYBOARD).setLanguage(ProofreaderOptions.Language.ENGLISH).build())
                try {
                    if (p.checkFeatureStatus().await() != FeatureStatus.AVAILABLE) return null
                    p.runInference(ProofreadingRequest.builder(input).build()).await().results.firstOrNull()?.text
                } finally { p.close() }
            } else {
                val type = when (style) {
                    "SHORTEN" -> RewriterOptions.OutputType.SHORTEN
                    "FRIENDLY" -> RewriterOptions.OutputType.FRIENDLY
                    "PROFESSIONAL" -> RewriterOptions.OutputType.PROFESSIONAL
                    "ELABORATE" -> RewriterOptions.OutputType.ELABORATE
                    else -> RewriterOptions.OutputType.REPHRASE
                }
                val w = Rewriting.getClient(RewriterOptions.builder(context).setOutputType(type).setLanguage(RewriterOptions.Language.ENGLISH).build())
                try {
                    if (w.checkFeatureStatus().await() != FeatureStatus.AVAILABLE) return null
                    w.runInference(RewritingRequest.builder(input).build()).await().results.firstOrNull()?.text
                } finally { w.close() }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** On phones with only the Summarization feature: one bullet for a tl;dr, three for a thread. */
    private suspend fun summaryFallback(r: LlmRequest): String? {
        if (r.task != AiTask.SUMMARIZE && r.task != AiTask.THREAD_SUMMARY) return null
        val conversation = r.hints["kind"] == "conversation"
        return runCatching {
            val s = Summarization.getClient(
                SummarizerOptions.builder(context)
                    .setInputType(if (conversation) SummarizerOptions.InputType.CONVERSATION else SummarizerOptions.InputType.ARTICLE)
                    .setOutputType(if (r.task == AiTask.SUMMARIZE) SummarizerOptions.OutputType.ONE_BULLET else SummarizerOptions.OutputType.THREE_BULLETS)
                    .setLanguage(SummarizerOptions.Language.ENGLISH)
                    .setLongInputAutoTruncationEnabled(true)
                    .build(),
            )
            try {
                if (s.checkFeatureStatus().await() != FeatureStatus.AVAILABLE) return null
                s.runInference(SummarizationRequest.builder(r.prompt).build()).await().summary
            } finally { s.close() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/**
 * A Gemma (or any MediaPipe-compatible) model file the person put on the phone — for devices
 * without AICore. `.task` bundles carry their own chat template; bare `.bin` weights get Gemma's.
 * One inference at a time: the model holds gigabytes, and a second copy would not fit.
 */
class LocalModelEngine(private val context: Context, private val path: () -> String?) : LlmEngine {
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

    fun close() {
        llm?.close(); llm = null; loaded = null
    }
}

/** ML Kit's on-device translator: works on every phone, needs a one-time ~30 MB language pack. */
class OnDeviceTranslator {
    data class Translated(val sourceLanguage: String, val text: String)

    suspend fun identify(text: String): String? = runCatching {
        LanguageIdentification.getClient().identifyLanguage(text.take(2000)).await().takeIf { it != "und" }
    }.getOrNull()

    suspend fun translate(text: String, target: String = Locale.getDefault().language): Translated? {
        val source = identify(text) ?: return null
        val s = TranslateLanguage.fromLanguageTag(source) ?: return null
        val t = TranslateLanguage.fromLanguageTag(target) ?: return null
        if (s == t) return null
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build())
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            // Paragraph by paragraph keeps the message's shape (and stays within the model's input).
            val out = text.split('\n').map { line -> if (line.isBlank()) line else translator.translate(line).await() }
            Translated(source, out.joinToString("\n"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            translator.close()
        }
    }
}
