package dev.aarso.mail.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration

/**
 * Claude through the official Anthropic SDK (Kotlin uses the Java SDK), with the person's own key.
 *
 * Defaults follow Anthropic's current guidance: `claude-opus-5-5` unless the person picks another
 * model; effort set per task (LOW for one-line summaries and classification, where it holds quality
 * at a fraction of the cost; MEDIUM for drafting); no `temperature` (removed on current models);
 * JSON tasks constrained with `output_config.format`; and server-side refusal fallbacks
 * (`fallbacks: "default"`) on the models that support them, so a declined request is retried on the
 * model Anthropic routes it to rather than simply failing.
 */
class AnthropicEngine(
    override val id: String,
    override val label: String,
    private val model: String,
    private val apiKey: String,
    private val baseUrl: String? = null,
    /** Server-side refusal fallbacks; null = on for supporting models on the Claude API itself. */
    private val serverFallbacks: Boolean? = null,
) : LlmEngine {

    override val onDevice = false
    override val maxInputTokens = 150_000
    override val supportsStructuredOutput = true

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .maxRetries(2)
            .timeout(Duration.ofSeconds(180))
            .apply { if (!baseUrl.isNullOrBlank()) baseUrl(baseUrl) }
            .build()
    }

    override suspend fun availability(): Availability =
        if (apiKey.isBlank()) Availability.Unavailable("No API key") else Availability.Ready

    /** Server-side fallback routing exists on these models, on the Claude API itself (not via a proxy). */
    private val fallbackCapable: Boolean
        get() = serverFallbacks ?: (baseUrl.isNullOrBlank() && model in FALLBACK_MODELS)

    private val effortCapable: Boolean
        get() = !LEGACY.containsMatchIn(model)

    private fun params(request: LlmRequest, withFormat: Boolean): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxOutputTokens.toLong())
            .system(request.system)
            .addUserMessage(request.prompt)
        if (fallbackCapable) b.addBeta(FALLBACK_BETA).fallbacksDefault()
        val config = BetaOutputConfig.builder()
        var hasConfig = false
        if (effortCapable) {
            config.effort(
                when (request.effort) {
                    Effort.LOW -> BetaOutputConfig.Effort.LOW
                    Effort.MEDIUM -> BetaOutputConfig.Effort.MEDIUM
                    Effort.HIGH -> BetaOutputConfig.Effort.HIGH
                },
            )
            hasConfig = true
        }
        if (withFormat && request.json != null) {
            config.format(BetaJsonOutputFormat.builder().schema(schemaOf(request.json.schema)).build())
            hasConfig = true
        }
        if (hasConfig) b.outputConfig(config.build())
        return b.build()
    }

    override suspend fun generate(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val message = try {
            call(params(request, withFormat = true))
        } catch (e: BadRequestException) {
            // An older model, or a gateway, that does not accept output_config.format: ask in words.
            if (request.json == null) throw classify(e)
            call(params(request, withFormat = false))
        }
        when (message.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL -> throw EngineException("Claude declined this request", retryable = true)
            BetaStopReason.MAX_TOKENS -> if (request.json != null) throw EngineException("Answer was cut off", retryable = true)
            else -> {}
        }
        val text = message.content().filter { it.isText() }.joinToString("") { it.asText().text() }
        if (text.isBlank()) throw EngineException("Empty answer", retryable = true)
        LlmResponse(text.trim(), id, onDevice = false, message.usage().inputTokens(), message.usage().outputTokens())
    }

    private fun call(p: MessageCreateParams) = try {
        client.beta().messages().create(p)
    } catch (e: BadRequestException) {
        throw e
    } catch (e: Exception) {
        throw classify(e)
    }

    /** Text deltas as Claude writes them (compose-assist streams into the editor). */
    override fun stream(request: LlmRequest): Flow<String> = flow {
        val p = params(request, withFormat = request.json != null)
        try {
            client.beta().messages().createStreaming(p).use { response ->
                val events = response.stream().iterator()
                while (events.hasNext()) {
                    val text = events.next().contentBlockDelta().flatMap { it.delta().text() }.map { it.text() }.orElse(null)
                    if (!text.isNullOrEmpty()) emit(text)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classify(e)
        }
    }.flowOn(Dispatchers.IO)

    /** The models this key can use, for the picker (live from GET /v1/models). */
    suspend fun listModels(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            client.models().list().autoPager().map { it.id() to it.displayName() }
        } catch (e: Exception) {
            throw classify(e)
        }
    }

    /** Most specific first, so the message a person sees is the useful one. */
    private fun classify(e: Exception): EngineException = when (e) {
        is EngineException -> e
        is UnauthorizedException -> EngineException("Anthropic rejected the API key", retryable = false, cause = e)
        is PermissionDeniedException -> EngineException("This key cannot use $model", retryable = false, cause = e)
        is NotFoundException -> EngineException("Model $model not found for this key", retryable = false, cause = e)
        is RateLimitException -> EngineException("Anthropic rate limit reached; try again shortly", retryable = true, cause = e)
        is BadRequestException -> EngineException("Anthropic rejected the request: ${e.message}", retryable = false, cause = e)
        is InternalServerException -> EngineException("Anthropic is having trouble right now", retryable = true, cause = e)
        is AnthropicServiceException -> EngineException("Anthropic error ${e.statusCode()}", retryable = e.statusCode() >= 500, cause = e)
        is AnthropicIoException -> EngineException("Could not reach Anthropic", retryable = true, cause = e)
        else -> EngineException(e.message ?: "Claude request failed", retryable = true, cause = e)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

        /** Models that predate the `effort` parameter. */
        private val LEGACY = Regex("claude-(3|instant|2)|claude-(haiku|sonnet)-4-5|claude-(opus|sonnet)-4-[0-1]|claude-opus-4\\b|claude-sonnet-4\\b")

        /** kotlinx JSON Schema → the SDK's schema object (the schema's top-level keys become its properties). */
        internal fun schemaOf(schema: JsonObject): BetaJsonOutputFormat.Schema {
            val b = BetaJsonOutputFormat.Schema.builder()
            for ((k, v) in schema) b.putAdditionalProperty(k, toJsonValue(v))
            return b.build()
        }

        internal fun toJsonValue(e: JsonElement): JsonValue = when (e) {
            is JsonNull -> JsonValue.from(null)
            is JsonPrimitive -> when {
                e.isString -> JsonValue.from(e.content)
                e.booleanOrNull != null -> JsonValue.from(e.booleanOrNull)
                e.longOrNull != null -> JsonValue.from(e.longOrNull)
                else -> JsonValue.from(e.doubleOrNull)
            }
            is JsonArray -> JsonValue.from(e.map { toJsonValue(it) })
            is JsonObject -> JsonValue.from(e.mapValues { toJsonValue(it.value) })
        }
    }
}
