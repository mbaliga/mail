package dev.aarso.mail.app.ai

import android.content.Context
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
import kotlinx.coroutines.withContext

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
