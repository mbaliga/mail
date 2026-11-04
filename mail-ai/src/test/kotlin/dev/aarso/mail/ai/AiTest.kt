package dev.aarso.mail.ai

import dev.aarso.mail.model.Category
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTest {

    private class FakeEngine(
        override val id: String,
        override val onDevice: Boolean,
        private val answer: (LlmRequest) -> String,
        private val ready: Boolean = true,
    ) : LlmEngine {
        override val label = id
        override val maxInputTokens = 4000
        override val supportsStructuredOutput = false
        val seen = ArrayList<LlmRequest>()
        override suspend fun availability() = if (ready) Availability.Ready else Availability.Unavailable("no")
        override suspend fun generate(request: LlmRequest): LlmResponse {
            seen += request
            return LlmResponse(answer(request), id, onDevice, 100, 20)
        }
    }

    private val msg = MessageHeader(
        id = 1, accountId = 1, mailboxId = 1, subject = "Re: Startup program question",
        from = EmailAddress("fin@anthropic.com", "Fin"), to = listOf(EmailAddress("me@example.org")),
        receivedAt = 1_793_534_400_000L, category = Category.REPLIES, preview = "Could you share more details so I can connect you to a human agent? Call +44 20 7946 0958.",
    )

    @Test fun `redactor hides identifiers and restores them`() {
        val r = Redactor()
        val text = "Mail ada@example.org or call +1 (415) 555-0142. Card 4111 1111 1111 1111, IBAN DE89 3704 0044 0532 0130 00. " +
            "Your code is 482913. Track at https://track.example.com/p/123?token=SECRET&u=ada"
        val out = r.redact(text)
        assertFalse(out.contains("ada@example.org"))
        assertFalse(out.contains("555-0142"))
        assertFalse(out.contains("4111 1111"))
        assertFalse(out.contains("DE89"))
        assertFalse(out.contains("482913"))
        assertFalse(out.contains("SECRET"))
        assertTrue(out.contains("https://track.example.com/p/123…"))
        assertEquals("Write to ada@example.org today", r.restore("Write to <email1> today"))
        assertFalse("non-Luhn digit runs are not cards", Redactor().redact("Order 1234 5678 9012 3456").contains("<card"))
    }

    @Test fun `router prefers on-device, guards categories, budgets and falls through failures`() = runBlocking {
        val nano = FakeEngine("nano", onDevice = true, answer = { "Fin, Anthropic's chatbot, asked for details to connect you to a human agent." })
        val cloud = FakeEngine("cloud", onDevice = false, answer = { "cloud answer" })
        var settings = AiSettings(cloudEngineId = "cloud")
        val ai = Intelligence({ listOf(cloud, nano) }, { settings })

        assertEquals(listOf("nano"), ai.plan(AiTask.SUMMARIZE).map { it.id })               // summaries never go to cloud by default
        assertEquals(listOf("nano", "cloud"), ai.plan(AiTask.DRAFT).map { it.id })
        assertEquals(listOf("nano"), ai.plan(AiTask.DRAFT, Sensitivity(Category.FINANCE, 1)).map { it.id })
        settings = settings.copy(preferOnDevice = false)
        assertEquals(listOf("cloud", "nano"), ai.plan(AiTask.DRAFT).map { it.id })
        settings = settings.copy(onDeviceEnabled = false, neverCloudAccounts = setOf(7))
        assertEquals(emptyList<String>(), ai.plan(AiTask.DRAFT, Sensitivity(null, 7)).map { it.id })

        // Budget: once today's cloud tokens are spent, cloud drops out of the plan.
        val ledger = UsageLedger()
        val tight = Intelligence({ listOf(cloud) }, { AiSettings(cloudEngineId = "cloud", dailyCloudTokens = 100) }, ledger)
        assertNotNull(tight.run(AiTask.DRAFT, Sensitivity.NONE, { _, _ -> LlmRequest(AiTask.DRAFT, "s", "p") }, { t, _ -> t }))
        assertTrue(tight.plan(AiTask.DRAFT).isEmpty())

        // Failure falls through to the next engine; a parse rejection does too.
        val broken = object : LlmEngine by nano {
            override val id = "broken"
            override suspend fun generate(request: LlmRequest): LlmResponse = throw EngineException("boom")
        }
        val chain = Intelligence({ listOf(broken, cloud) }, { AiSettings(cloudEngineId = "cloud") })
        assertEquals("cloud", chain.run(AiTask.DRAFT, Sensitivity.NONE, { _, _ -> LlmRequest(AiTask.DRAFT, "s", "p") }, { t, _ -> t })?.engineId)
        assertNull(chain.run(AiTask.SUMMARIZE, Sensitivity.NONE, { _, _ -> LlmRequest(AiTask.SUMMARIZE, "s", "p") }, { t, _ -> t }))
    }

    @Test fun `cloud prompts are redacted and the summary parser takes one clean line`() = runBlocking {
        val cloud = FakeEngine("cloud", onDevice = false, answer = { "\"Fin, Anthropic's chatbot, asked about the startup program and for details to connect you to a human agent.\"\nExtra" })
        val ai = MailAi(Intelligence({ listOf(cloud) }, { AiSettings(cloudEngineId = "cloud", cloudTasks = setOf(AiTask.SUMMARIZE)) }))
        val r = ai.tldr(msg, null)!!
        assertEquals("Fin, Anthropic's chatbot, asked about the startup program and for details to connect you to a human agent.", r.value)
        assertFalse(r.onDevice)
        val sent = cloud.seen.single().prompt
        assertFalse("addresses never reach the cloud", sent.contains("fin@anthropic.com"))
        assertFalse(sent.contains("7946"))
    }

    @Test fun `rules and categories parse into the model, invalid answers are ignored`() = runBlocking {
        val engine = FakeEngine("nano", onDevice = true, answer = { req ->
            when (req.task) {
                AiTask.RULE_FROM_TEXT -> """{"name":"Old newsletters","query":"category:newsletters older_than:7d -from:stratechery","trigger":"scheduled","interval_minutes":1440,"actions":[{"type":"archive","value":""},{"type":"mark_read","value":""}]}"""
                AiTask.CATEGORIZE -> """```json
                    {"category": "tickets", "confidence": 0.82}
                    ```"""
                else -> "I think this is about a lot of things."
            }
        })
        val ai = MailAi(Intelligence({ listOf(engine) }, { AiSettings() }))
        val rule = ai.toRule("Every night archive newsletters older than a week except Stratechery")!!.value
        assertEquals("category:newsletters older_than:7d -from:stratechery", rule.query)
        assertEquals(listOf(RuleAction.Archive, RuleAction.MarkRead), rule.actions)
        assertEquals(setOf(RuleTrigger.SCHEDULED), rule.triggers)
        assertEquals(Category.TICKETS to 0.82f, ai.categorize(msg, null)!!.value)
        assertNull("prose is not a valid reply list", ai.replySuggestions(msg, null, "Me"))
    }

    @Test fun `claude request carries model, effort, schema and server-side fallbacks`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":"{\"category\":\"tickets\",\"confidence\":0.9}"}],
               "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":120,"output_tokens":14}}""",
        ))
        server.start()
        try {
            val engine = AnthropicEngine("anthropic", "Claude", AnthropicEngine.DEFAULT_MODEL, "sk-test", server.url("/").toString().trimEnd('/'), serverFallbacks = true)
            val schema = Json.parseToJsonElement("""{"type":"object","properties":{"category":{"type":"string"}},"required":["category"],"additionalProperties":false}""").jsonObject
            val out = engine.generate(LlmRequest(AiTask.CATEGORIZE, "sys", "classify this", 4096, JsonSpec("category", schema), Effort.LOW))
            assertEquals("{\"category\":\"tickets\",\"confidence\":0.9}", out.text)
            assertEquals(134L, out.inputTokens + out.outputTokens)
            val req = server.takeRequest()
            assertEquals("/v1/messages", req.path?.substringBefore('?'))
            // The SDK (3.x) presents the key as a bearer credential and pins its own API version.
            assertEquals("Bearer sk-test", req.getHeader("authorization"))
            assertNotNull(req.getHeader("anthropic-version"))
            assertTrue(req.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
            val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
            assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
            assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals("json_schema", body["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertNull("no sampling parameters on current models", body["temperature"])
        } finally {
            server.shutdown()
        }
    }

    @Test fun `openai compatible and gemini speak their own wire formats`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Hello there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}"""))
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\ndata: [DONE]\n\n"))
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"{\"query\":\"is:unread\"}"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":4}}"""))
        server.start()
        try {
            val base = server.url("/v1").toString()
            val openai = OpenAiCompatibleEngine("oa", "OpenAI", CloudKind.OPENAI, base, "some-model", "key")
            val schema = Json.parseToJsonElement("""{"type":"object","properties":{"q":{"type":"string"}},"required":["q"],"additionalProperties":false}""").jsonObject
            assertEquals("Hello there", openai.generate(LlmRequest(AiTask.DRAFT, "s", "p", json = JsonSpec("x", schema))).text)
            val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("json_schema", first["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertNotNull(first["max_completion_tokens"])
            assertEquals(listOf("Hel", "lo"), openai.stream(LlmRequest(AiTask.DRAFT, "s", "p")).toList())
            server.takeRequest()

            val gemini = GeminiEngine("g", "Gemini", "gemini-2.5-flash", "gk", server.url("/v1beta").toString())
            assertEquals("{\"query\":\"is:unread\"}", gemini.generate(LlmRequest(AiTask.SEARCH_QUERY, "s", "p", json = JsonSpec("q", schema))).text)
            val g = server.takeRequest()
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", g.path)
            assertEquals("gk", g.getHeader("x-goog-api-key"))
            val gBody = g.body.readUtf8()
            assertFalse("Gemini's schema dialect has no additionalProperties", gBody.contains("additionalProperties"))
        } finally {
            server.shutdown()
        }
    }
}
