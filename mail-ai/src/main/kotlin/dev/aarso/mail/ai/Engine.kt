package dev.aarso.mail.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/** How hard a model should think. Maps to Claude's `output_config.effort`; other engines ignore it. */
enum class Effort { LOW, MEDIUM, HIGH }

/** What the person's mail is being used for — the unit policy is written in. */
enum class AiTask(val label: String, val cloudAllowedByDefault: Boolean) {
    SUMMARIZE("tl;dr summaries", false),
    THREAD_SUMMARY("conversation summaries", false),
    CATEGORIZE("sorting into tabs", false),
    EXTRACT("dates, tasks and codes", false),
    REPLY_SUGGESTIONS("quick replies", false),
    DRAFT("writing drafts", true),
    REWRITE("rewriting and proofreading", true),
    TRANSLATE("translation", true),
    SEARCH_QUERY("understanding searches", true),
    RULE_FROM_TEXT("writing rules from a sentence", true),
    ASK_INBOX("answering questions about mail", true),
    BRIEFING("the spoken briefing", false),
}

/**
 * A structured-output request: the engine must answer with JSON matching [schema] (JSON Schema,
 * `additionalProperties: false`, every property required). Engines that can enforce it do
 * (Claude's `output_config.format`, OpenAI `json_schema`, Gemini `responseSchema`); on-device
 * models are instructed and their answer is parsed leniently and validated by the caller.
 */
data class JsonSpec(val name: String, val schema: JsonObject)

data class LlmRequest(
    val task: AiTask,
    val system: String,
    val prompt: String,
    val maxOutputTokens: Int = 1024,
    val json: JsonSpec? = null,
    val effort: Effort = Effort.LOW,
    /**
     * Task-specific hints for engines with purpose-built APIs — Gemini Nano's summariser, rewriter
     * and proofreader take the raw text and a style rather than a prompt: `input`, `style`, `kind`.
     */
    val hints: Map<String, String> = emptyMap(),
)

data class LlmResponse(
    val text: String,
    val engineId: String,
    val onDevice: Boolean,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

sealed interface Availability {
    data object Ready : Availability
    /** The model exists for this device but must be downloaded (Gemini Nano via AICore, a Gemma file). */
    data class Downloadable(val bytes: Long?) : Availability
    data class Downloading(val progress: Float?) : Availability
    data class Unavailable(val reason: String) : Availability
}

/** An engine refused or failed; [retryable] distinguishes "try another engine now" from "stop". */
class EngineException(message: String, val retryable: Boolean = true, cause: Throwable? = null) : Exception(message, cause)

/**
 * One language model, wherever it runs. On-device engines (Gemini Nano through ML Kit GenAI, a local
 * Gemma model through MediaPipe) are implemented in the Android app; the BYOK cloud engines live in
 * this module. The router treats them identically except for one bit — [onDevice] — which decides
 * what they are allowed to see.
 */
interface LlmEngine {
    val id: String
    /** What a person reads: "Gemini Nano on this phone", "Claude (your Anthropic key)". */
    val label: String
    val onDevice: Boolean
    /** Rough input budget in tokens; inputs are trimmed to it before the call. */
    val maxInputTokens: Int
    val supportsStructuredOutput: Boolean

    suspend fun availability(): Availability
    suspend fun generate(request: LlmRequest): LlmResponse

    /** Streaming text deltas. Engines without native streaming emit the whole answer once. */
    fun stream(request: LlmRequest): Flow<String> = flow { emit(generate(request).text) }

    /** Ask for the model to be fetched (on-device engines). Cloud engines have nothing to download. */
    suspend fun download(onProgress: (Float?) -> Unit = {}) {}
}

/** ~4 characters per token for Latin script, ~1.5 for CJK — a conservative trim estimate, not a tokenizer. */
fun estimateTokens(text: String): Int {
    var cjk = 0
    for (c in text) if (Character.UnicodeScript.of(c.code).let { it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA || it == Character.UnicodeScript.HANGUL }) cjk++
    return ((text.length - cjk) / 4.0 + cjk / 1.5).toInt() + 1
}

/** Trim [text] to roughly [tokens], keeping the beginning (where mail puts what matters). */
fun trimToTokens(text: String, tokens: Int): String {
    if (estimateTokens(text) <= tokens) return text
    var lo = 0
    var hi = text.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (estimateTokens(text.substring(0, mid)) <= tokens) lo = mid else hi = mid - 1
    }
    return text.substring(0, lo).substringBeforeLast(' ', text.substring(0, lo)) + " …"
}
