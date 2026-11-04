package dev.aarso.mail.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Which wire API a BYOK engine speaks. */
@Serializable
enum class CloudKind { ANTHROPIC, OPENAI, GEMINI, OPENAI_COMPATIBLE }

/** A provider the setup screen offers, with sensible endpoints. Models are listed live from the key. */
data class CloudPreset(val id: String, val name: String, val kind: CloudKind, val baseUrl: String, val defaultModel: String, val keyUrl: String?, val needsKey: Boolean = true)

object CloudPresets {
    val all = listOf(
        CloudPreset("anthropic", "Anthropic (Claude)", CloudKind.ANTHROPIC, "", AnthropicEngine.DEFAULT_MODEL, "https://platform.claude.com/settings/keys"),
        CloudPreset("openai", "OpenAI", CloudKind.OPENAI, "https://api.openai.com/v1", "", "https://platform.openai.com/api-keys"),
        CloudPreset("gemini", "Google Gemini", CloudKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", "https://aistudio.google.com/apikey"),
        CloudPreset("mistral", "Mistral", CloudKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1", "", "https://console.mistral.ai/api-keys"),
        CloudPreset("groq", "Groq", CloudKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "", "https://console.groq.com/keys"),
        CloudPreset("openrouter", "OpenRouter", CloudKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "", "https://openrouter.ai/keys"),
        CloudPreset("deepseek", "DeepSeek", CloudKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", "", "https://platform.deepseek.com/api_keys"),
        CloudPreset("xai", "xAI", CloudKind.OPENAI_COMPATIBLE, "https://api.x.ai/v1", "", "https://console.x.ai"),
        CloudPreset("together", "Together", CloudKind.OPENAI_COMPATIBLE, "https://api.together.xyz/v1", "", "https://api.together.ai/settings/api-keys"),
        // Self-hosted on the person's own network: still "cloud" to the phone (it leaves the device).
        CloudPreset("ollama", "Ollama (your computer)", CloudKind.OPENAI_COMPATIBLE, "http://192.168.1.10:11434/v1", "", null, needsKey = false),
        CloudPreset("lmstudio", "LM Studio (your computer)", CloudKind.OPENAI_COMPATIBLE, "http://192.168.1.10:1234/v1", "", null, needsKey = false),
        CloudPreset("custom", "Any OpenAI-compatible server", CloudKind.OPENAI_COMPATIBLE, "https://", "", null),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** A configured BYOK engine. The key itself lives in the secret store under [keyRef]. */
@Serializable
data class CloudEngineConfig(
    val id: String,
    val presetId: String,
    val kind: CloudKind,
    val label: String,
    val model: String,
    val baseUrl: String = "",
    val keyRef: String,
    val enabled: Boolean = true,
)

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val JSON_TYPE = "application/json".toMediaType()

private fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .build()

private fun Response.failure(provider: String): EngineException = when (code) {
    401, 403 -> EngineException("$provider rejected the API key ($code)", retryable = false)
    404 -> EngineException("$provider: model or endpoint not found", retryable = false)
    429 -> EngineException("$provider rate limit reached", retryable = true)
    in 500..599 -> EngineException("$provider is having trouble ($code)", retryable = true)
    else -> EngineException("$provider error $code: ${body?.string()?.take(200)}", retryable = false)
}

/**
 * OpenAI's Chat Completions shape, which OpenAI, Mistral, Groq, OpenRouter, DeepSeek, xAI, Together,
 * Ollama, LM Studio and vLLM all accept. Structured output uses `response_format: json_schema` on
 * OpenAI itself and plain instructions elsewhere (support for the strict form varies by server).
 */
class OpenAiCompatibleEngine(
    override val id: String,
    override val label: String,
    private val kind: CloudKind,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String?,
    private val http: OkHttpClient = defaultHttp(),
) : LlmEngine {
    override val onDevice = false
    override val maxInputTokens = 60_000
    override val supportsStructuredOutput = kind == CloudKind.OPENAI
    private val base = baseUrl.trimEnd('/')

    override suspend fun availability(): Availability = when {
        model.isBlank() -> Availability.Unavailable("Choose a model")
        kind != CloudKind.OPENAI_COMPATIBLE && apiKey.isNullOrBlank() -> Availability.Unavailable("No API key")
        else -> Availability.Ready
    }

    private fun body(request: LlmRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", request.system) })
            add(buildJsonObject { put("role", "user"); put("content", request.prompt) })
        }
        // OpenAI's reasoning models take max_completion_tokens; everything else understands max_tokens.
        if (kind == CloudKind.OPENAI) put("max_completion_tokens", request.maxOutputTokens) else put("max_tokens", request.maxOutputTokens)
        if (stream) put("stream", true)
        if (request.json != null && kind == CloudKind.OPENAI) {
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", request.json.name)
                    put("schema", request.json.schema)
                    put("strict", true)
                }
            }
        }
    }

    private fun request(path: String, payload: JsonObject?): Request = Request.Builder()
        .url("$base$path")
        .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
        .apply { if (payload != null) post(payload.toString().toRequestBody(JSON_TYPE)) else get() }
        .build()

    override suspend fun generate(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val resp = try { http.newCall(request("/chat/completions", body(request, false))).execute() } catch (e: java.io.IOException) {
            throw EngineException("Could not reach $label", retryable = true, cause = e)
        }
        resp.use { r ->
            if (!r.isSuccessful) throw r.failure(label)
            val o = json.parseToJsonElement(r.body!!.string()).jsonObject
            val choice = o["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val text = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "content_filter") throw EngineException("$label declined", retryable = true)
            if (text.isBlank()) throw EngineException("Empty answer", retryable = true)
            val usage = o["usage"]?.jsonObject
            LlmResponse(text.trim(), id, false, usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0, usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0)
        }
    }

    override fun stream(request: LlmRequest): Flow<String> = flow {
        val resp = http.newCall(request("/chat/completions", body(request, true))).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw r.failure(label)
            val source = r.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = runCatching {
                    json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (!delta.isNullOrEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun listModels(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        http.newCall(request("/models", null)).execute().use { r ->
            if (!r.isSuccessful) throw r.failure(label)
            json.parseToJsonElement(r.body!!.string()).jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .sorted().map { it to it }
        }
    }
}

/** Google's Gemini API (generateContent), with its own schema dialect for structured output. */
class GeminiEngine(
    override val id: String,
    override val label: String,
    private val model: String,
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val http: OkHttpClient = defaultHttp(),
) : LlmEngine {
    override val onDevice = false
    override val maxInputTokens = 200_000
    override val supportsStructuredOutput = true
    private val base = baseUrl.trimEnd('/')

    override suspend fun availability(): Availability =
        if (apiKey.isBlank()) Availability.Unavailable("No API key") else if (model.isBlank()) Availability.Unavailable("Choose a model") else Availability.Ready

    private fun body(request: LlmRequest): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", request.system) }) } }
        putJsonArray("contents") {
            add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", request.prompt) }) } })
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", request.maxOutputTokens)
            if (request.json != null) {
                put("responseMimeType", "application/json")
                put("responseSchema", geminiSchema(request.json.schema))
            }
        }
    }

    /** Gemini's OpenAPI-subset schema has no `additionalProperties`; drop it recursively. */
    private fun geminiSchema(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it != "additionalProperties" }.mapValues { geminiSchema(it.value) })
        is JsonArray -> JsonArray(e.map { geminiSchema(it) })
        else -> e
    }

    private fun model() = if (model.startsWith("models/")) model else "models/$model"

    override suspend fun generate(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/${model()}:generateContent").header("x-goog-api-key", apiKey)
            .post(body(request).toString().toRequestBody(JSON_TYPE)).build()
        val resp = try { http.newCall(req).execute() } catch (e: java.io.IOException) { throw EngineException("Could not reach Gemini", true, e) }
        resp.use { r ->
            if (!r.isSuccessful) throw r.failure("Gemini")
            val o = json.parseToJsonElement(r.body!!.string()).jsonObject
            val cand = o["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            if (cand?.get("finishReason")?.jsonPrimitive?.contentOrNull in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST")) throw EngineException("Gemini declined", true)
            val text = cand?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
            if (text.isBlank()) throw EngineException("Empty answer", true)
            val usage = o["usageMetadata"]?.jsonObject
            LlmResponse(text.trim(), id, false, usage?.get("promptTokenCount")?.jsonPrimitive?.longOrNull ?: 0, usage?.get("candidatesTokenCount")?.jsonPrimitive?.longOrNull ?: 0)
        }
    }

    override fun stream(request: LlmRequest): Flow<String> = flow {
        val req = Request.Builder().url("$base/${model()}:streamGenerateContent?alt=sse").header("x-goog-api-key", apiKey)
            .post(body(request).toString().toRequestBody(JSON_TYPE)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw r.failure("Gemini")
            val source = r.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val text = runCatching {
                    json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty().mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
                }.getOrNull()
                if (!text.isNullOrEmpty()) emit(text)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun listModels(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/models?pageSize=200").header("x-goog-api-key", apiKey).get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw r.failure("Gemini")
            json.parseToJsonElement(r.body!!.string()).jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { m ->
                val o = m.jsonObject
                val methods = o["supportedGenerationMethods"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                if ("generateContent" !in methods) return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.content?.removePrefix("models/") ?: return@mapNotNull null
                name to (o["displayName"]?.jsonPrimitive?.contentOrNull ?: name)
            }
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/** Build the engine a config describes. */
object CloudEngineFactory {
    fun create(config: CloudEngineConfig, apiKey: String?): LlmEngine = when (config.kind) {
        CloudKind.ANTHROPIC -> AnthropicEngine(config.id, config.label, config.model.ifBlank { AnthropicEngine.DEFAULT_MODEL }, apiKey.orEmpty(), config.baseUrl.ifBlank { null })
        CloudKind.GEMINI -> GeminiEngine(config.id, config.label, config.model, apiKey.orEmpty(), config.baseUrl.ifBlank { "https://generativelanguage.googleapis.com/v1beta" })
        CloudKind.OPENAI, CloudKind.OPENAI_COMPATIBLE -> OpenAiCompatibleEngine(config.id, config.label, config.kind, config.baseUrl.ifBlank { "https://api.openai.com/v1" }, config.model, apiKey)
    }

    suspend fun listModels(engine: LlmEngine): List<Pair<String, String>> = when (engine) {
        is AnthropicEngine -> engine.listModels()
        is OpenAiCompatibleEngine -> engine.listModels()
        is GeminiEngine -> engine.listModels()
        else -> emptyList()
    }
}
