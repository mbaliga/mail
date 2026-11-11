package dev.aarso.mail.ai

import dev.aarso.mail.digest.Digest
import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.ClassificationSource
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.NotifyLevel
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import dev.aarso.mail.search.MailSearch
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.store.MailStore
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RewriteStyle(val instruction: String, val nano: String) {
    SHORTER("Make it shorter and tighter.", "SHORTEN"),
    FRIENDLIER("Make it warmer and friendlier.", "FRIENDLY"),
    FORMAL("Make it more formal and professional.", "PROFESSIONAL"),
    CLEARER("Make it clearer and easier to follow.", "REPHRASE"),
    LONGER("Expand it with a little more detail.", "ELABORATE"),
    PROOFREAD("Fix spelling, grammar and punctuation only. Change nothing else.", "PROOFREAD"),
}

data class Answer(val text: String, val sources: List<MessageHeader>)
data class ExtractedEvent(val title: String, val start: String?, val end: String?, val location: String?)
data class ExtractedTask(val text: String, val due: String?)
data class Extraction(val events: List<ExtractedEvent>, val tasks: List<ExtractedTask>)

/**
 * Every mail task a model can help with, as prompt + schema + parser, routed through [Intelligence].
 *
 * Each returns null when no engine may or can do it — and every caller has a non-model answer to
 * fall back to (the heuristic tl;dr, the rule-based categoriser, the template briefing). Output is
 * validated before use: a model that answers a category question with a paragraph is ignored, not
 * trusted.
 */
class MailAi(
    private val intelligence: Intelligence,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun sensitivity(h: MessageHeader) = Sensitivity(h.category, h.accountId)

    private fun when_(ms: Long): String =
        if (ms <= 0) "" else DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))

    /** The message as a model sees it: envelope and the author's new words, sized to the engine. */
    private fun render(h: MessageHeader, body: MessageBody?, engine: LlmEngine, r: Redactor?, budget: Int = engine.maxInputTokens - 600): String {
        val text = body?.text ?: body?.html?.let { HtmlText.toText(it) } ?: h.preview
        val content = trimToTokens(HtmlText.newContent(text), budget.coerceAtLeast(300))
        val raw = buildString {
            append("From: ").append(h.from?.toString() ?: "unknown").append('\n')
            if (h.to.isNotEmpty()) append("To: ").append(h.to.take(5).joinToString { it.toString() }).append('\n')
            append("Date: ").append(when_(h.receivedAt)).append('\n')
            append("Subject: ").append(h.subject).append("\n\n")
            append(content)
        }
        return r?.redact(raw) ?: raw
    }

    private fun parseJson(text: String): JsonObject? {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOf('{'); val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(t.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun schema(vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { props.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.first)) } }
        put("additionalProperties", false)
    }
    private fun str(desc: String? = null) = buildJsonObject { put("type", "string"); desc?.let { put("description", it) } }
    private fun num() = buildJsonObject { put("type", "number") }
    private fun enumOf(values: List<String>) = buildJsonObject { put("type", "string"); putJsonArray("enum") { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
    private fun arrayOf(items: JsonObject) = buildJsonObject { put("type", "array"); put("items", items) }

    // ------------------------------------------------------------------------------------------
    // tl;dr — the line in the digest list
    // ------------------------------------------------------------------------------------------

    suspend fun tldr(h: MessageHeader, body: MessageBody?): AiResult<String>? = intelligence.run(
        AiTask.SUMMARIZE, sensitivity(h),
        build = { engine, r ->
            LlmRequest(
                task = AiTask.SUMMARIZE,
                system = TLDR_SYSTEM,
                prompt = render(h, body, engine, r, budget = minOf(engine.maxInputTokens - 400, 3000)),
                maxOutputTokens = 4096,
                hints = mapOf("kind" to if (h.category == Category.REPLIES || h.category == Category.PEOPLE) "conversation" else "article"),
            )
        },
        parse = { text, r ->
            val line = (r?.restore(text) ?: text).lines().map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim() }
                .firstOrNull { it.isNotEmpty() }?.trim('"', '“', '”')
            line?.takeIf { it.length in 8..240 }
        },
    )

    // ------------------------------------------------------------------------------------------
    // Category refinement — only consulted when the heuristic is unsure
    // ------------------------------------------------------------------------------------------

    suspend fun categorize(h: MessageHeader, body: MessageBody?): AiResult<Pair<Category, Float>>? = intelligence.run(
        AiTask.CATEGORIZE, sensitivity(h),
        build = { engine, r ->
            LlmRequest(
                task = AiTask.CATEGORIZE, system = CATEGORY_SYSTEM,
                prompt = render(h, body, engine, r, budget = minOf(engine.maxInputTokens - 600, 2500)),
                maxOutputTokens = 4096,
                json = JsonSpec("category", schema("category" to enumOf(Category.entries.map { it.key }), "confidence" to num())),
            )
        },
        parse = { text, _ ->
            val o = parseJson(text) ?: return@run null
            val c = o["category"]?.jsonPrimitive?.contentOrNull?.let { Category.fromKey(it) } ?: return@run null
            c to (o["confidence"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0.6f).coerceIn(0f, 1f)
        },
    )

    // ------------------------------------------------------------------------------------------
    // Conversations
    // ------------------------------------------------------------------------------------------

    suspend fun threadSummary(messages: List<Pair<MessageHeader, MessageBody?>>): AiResult<String>? {
        if (messages.isEmpty()) return null
        val first = messages.first().first
        return intelligence.run(
            AiTask.THREAD_SUMMARY, sensitivity(first),
            build = { engine, r ->
                val per = ((engine.maxInputTokens - 800) / messages.size).coerceIn(200, 2500)
                LlmRequest(
                    task = AiTask.THREAD_SUMMARY, system = THREAD_SYSTEM,
                    prompt = messages.joinToString("\n\n---\n\n") { (h, b) -> render(h, b, engine, r, per) },
                    maxOutputTokens = 8000, effort = Effort.LOW,
                    hints = mapOf("kind" to "conversation"),
                )
            },
            parse = { text, r -> (r?.restore(text) ?: text).trim().takeIf { it.length > 10 } },
        )
    }

    suspend fun replySuggestions(h: MessageHeader, body: MessageBody?, myName: String): AiResult<List<String>>? = intelligence.run(
        AiTask.REPLY_SUGGESTIONS, sensitivity(h),
        build = { engine, r ->
            LlmRequest(
                task = AiTask.REPLY_SUGGESTIONS,
                system = "You suggest three short replies $myName could send to this email. Each reply is a complete, natural message of at most 12 words, " +
                    "in the email's own language. Make the three meaningfully different (for example: accept, decline, ask a question). " +
                    "Answer with JSON: {\"replies\": [\"…\", \"…\", \"…\"]}.",
                prompt = render(h, body, engine, r, budget = minOf(engine.maxInputTokens - 500, 2500)),
                maxOutputTokens = 4096,
                json = JsonSpec("replies", schema("replies" to arrayOf(str()))),
            )
        },
        parse = { text, r ->
            val o = parseJson(text) ?: return@run null
            o["replies"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.let { s -> r?.restore(s) ?: s }?.trim() }
                ?.filter { it.isNotEmpty() && it.length < 160 }?.take(3)?.takeIf { it.isNotEmpty() }
        },
    )

    // ------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------

    private fun draftRequest(intent: String, replyingTo: Pair<MessageHeader, MessageBody?>?, myName: String, language: String?, engine: LlmEngine, r: Redactor?): LlmRequest {
        val context = replyingTo?.let { (h, b) -> "\n\nYou are replying to this email:\n" + render(h, b, engine, r, budget = minOf(engine.maxInputTokens - 1000, 6000)) }.orEmpty()
        val ask = r?.redact(intent) ?: intent
        return LlmRequest(
            task = AiTask.DRAFT,
            system = "You draft emails for $myName. Write only the message body — no subject line, no placeholders like [Name], no commentary. " +
                "Write in $myName's voice: clear, warm, direct, and as short as the purpose allows. " +
                (language?.let { "Write in $it. " } ?: "Match the language of the conversation. ") +
                "Sign off with \"$myName\".",
            prompt = "What $myName wants to say: $ask$context",
            maxOutputTokens = 16000, effort = Effort.MEDIUM,
        )
    }

    fun draftStream(intent: String, replyingTo: Pair<MessageHeader, MessageBody?>?, myName: String, language: String? = null, onEngine: (LlmEngine) -> Unit = {}): Flow<String> =
        intelligence.stream(AiTask.DRAFT, replyingTo?.first?.let(::sensitivity) ?: Sensitivity.NONE,
            build = { engine, r -> draftRequest(intent, replyingTo, myName, language, engine, r) }, onEngine = onEngine)

    suspend fun draft(intent: String, replyingTo: Pair<MessageHeader, MessageBody?>?, myName: String, language: String? = null): AiResult<String>? =
        intelligence.run(AiTask.DRAFT, replyingTo?.first?.let(::sensitivity) ?: Sensitivity.NONE,
            build = { engine, r -> draftRequest(intent, replyingTo, myName, language, engine, r) },
            parse = { text, r -> (r?.restore(text) ?: text).trim().takeIf { it.isNotEmpty() } })

    suspend fun rewrite(text: String, style: RewriteStyle): AiResult<String>? = intelligence.run(
        AiTask.REWRITE, Sensitivity.NONE,
        build = { engine, r ->
            val input = trimToTokens(r?.redact(text) ?: text, engine.maxInputTokens - 400)
            LlmRequest(
                task = AiTask.REWRITE,
                system = "Rewrite the user's text. ${style.instruction} Keep its meaning, its language and any names. Output only the rewritten text.",
                prompt = input, maxOutputTokens = 8000,
                hints = mapOf("input" to input, "style" to style.nano),
            )
        },
        parse = { out, r -> (r?.restore(out) ?: out).trim().takeIf { it.isNotEmpty() } },
    )

    suspend fun translate(text: String, toLanguage: String): AiResult<String>? = intelligence.run(
        AiTask.TRANSLATE, Sensitivity.NONE,
        build = { engine, r ->
            LlmRequest(AiTask.TRANSLATE, "Translate the user's text into $toLanguage. Keep formatting and names. Output only the translation.",
                trimToTokens(r?.redact(text) ?: text, engine.maxInputTokens - 400), maxOutputTokens = 16000)
        },
        parse = { out, r -> (r?.restore(out) ?: out).trim().takeIf { it.isNotEmpty() } },
    )

    // ------------------------------------------------------------------------------------------
    // Language → structure
    // ------------------------------------------------------------------------------------------

    /** A sentence → a query in the shared grammar. The result is shown as editable chips before running. */
    suspend fun toQuery(sentence: String, todayIso: String): AiResult<String>? = intelligence.run(
        AiTask.SEARCH_QUERY, Sensitivity.NONE,
        build = { _, _ ->
            LlmRequest(AiTask.SEARCH_QUERY, QUERY_SYSTEM + "\nToday is $todayIso.", sentence, maxOutputTokens = 4096,
                json = JsonSpec("query", schema("query" to str("the search query"))))
        },
        parse = { text, _ -> parseJson(text)?.get("query")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it.length < 500 } },
    )

    /** "Archive newsletters older than a week except Stratechery" → a rule, for review before saving. */
    suspend fun toRule(sentence: String): AiResult<MailRule>? = intelligence.run(
        AiTask.RULE_FROM_TEXT, Sensitivity.NONE,
        build = { _, _ ->
            LlmRequest(
                AiTask.RULE_FROM_TEXT, RULE_SYSTEM, sentence, maxOutputTokens = 4096,
                json = JsonSpec(
                    "rule",
                    schema(
                        "name" to str(), "query" to str(),
                        "trigger" to enumOf(listOf("new_mail", "scheduled")),
                        "interval_minutes" to buildJsonObject { put("type", "integer") },
                        "actions" to arrayOf(schema("type" to enumOf(ACTION_TYPES), "value" to str("folder, label, minutes, address or text; empty if not needed"))),
                    ),
                ),
            )
        },
        parse = { text, _ ->
            val o = parseJson(text) ?: return@run null
            val query = o["query"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@run null
            val actions = o["actions"]?.jsonArray?.mapNotNull { a -> action(a.jsonObject) }.orEmpty()
            if (actions.isEmpty()) return@run null
            val scheduled = o["trigger"]?.jsonPrimitive?.contentOrNull == "scheduled"
            MailRule(
                name = o["name"]?.jsonPrimitive?.contentOrNull?.take(80) ?: sentence.take(60),
                query = query, actions = actions,
                triggers = setOf(if (scheduled) RuleTrigger.SCHEDULED else RuleTrigger.NEW_MAIL),
                intervalMinutes = o["interval_minutes"]?.jsonPrimitive?.intOrNull?.coerceIn(15, 7 * 1440) ?: 1440,
            )
        },
    )

    private fun action(o: JsonObject): RuleAction? {
        val v = o["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (o["type"]?.jsonPrimitive?.contentOrNull) {
            "mark_read" -> RuleAction.MarkRead
            "mark_unread" -> RuleAction.MarkUnread
            "star" -> RuleAction.Star
            "archive" -> RuleAction.Archive
            "trash" -> RuleAction.Trash
            "spam" -> RuleAction.Spam
            "pin" -> RuleAction.Pin
            "mute" -> RuleAction.Mute
            "move" -> if (v.isBlank()) null else MailboxRole.entries.firstOrNull { it.name.equals(v, true) }?.let { RuleAction.MoveTo(role = it) } ?: RuleAction.MoveTo(mailbox = v)
            "label" -> v.takeIf { it.isNotBlank() }?.let { RuleAction.AddLabel(it) }
            "categorize" -> Category.fromKey(v)?.let { RuleAction.Categorize(it) }
            "snooze" -> v.toIntOrNull()?.let { RuleAction.Snooze(it) }
            "notify_urgent" -> RuleAction.Notify(NotifyLevel.URGENT)
            "notify_silent" -> RuleAction.Notify(NotifyLevel.SILENT)
            "never_notify" -> RuleAction.Notify(NotifyLevel.NEVER)
            "forward" -> v.takeIf { it.contains('@') }?.let { RuleAction.Forward(it) }
            "summarize" -> RuleAction.Summarize
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ask your inbox
    // ------------------------------------------------------------------------------------------

    /**
     * Answer a question from the mail itself: turn it into a query (model, or the natural-language
     * grammar as fallback), retrieve, and answer citing only what was retrieved.
     */
    suspend fun ask(question: String, search: MailSearch, store: MailStore, scope: SearchScope = SearchScope(), todayIso: String): AiResult<Answer>? {
        val query = toQuery(question, todayIso)?.value
        var hits = search.search(search.parse(query ?: question), scope, limit = 8).headers
        if (hits.isEmpty() && query != null) hits = search.search(search.parse(question), scope, limit = 8).headers
        if (hits.isEmpty()) return null
        val bodies = hits.associate { it.id to store.body(it.id) }
        val sens = hits.firstOrNull { it.category in setOf(Category.FINANCE, Category.SECURITY, Category.OTP) }?.let(::sensitivity) ?: Sensitivity(null, hits.first().accountId)
        return intelligence.run(
            AiTask.ASK_INBOX, sens,
            build = { engine, r ->
                val per = ((engine.maxInputTokens - 1200) / hits.size).coerceIn(150, 3000)
                val context = hits.mapIndexed { i, h -> "[${i + 1}]\n" + render(h, bodies[h.id], engine, r, per) }.joinToString("\n\n")
                LlmRequest(
                    AiTask.ASK_INBOX,
                    "Answer the reader's question using only the numbered emails below. Be brief and specific; quote figures, dates and names exactly. " +
                        "If the emails do not contain the answer, say so plainly. Answer with JSON: {\"answer\": \"…\", \"sources\": [numbers of the emails you used]}.",
                    "Question: ${r?.redact(question) ?: question}\n\n$context",
                    maxOutputTokens = 16000, effort = Effort.MEDIUM,
                    json = JsonSpec("answer", schema("answer" to str(), "sources" to arrayOf(buildJsonObject { put("type", "integer") }))),
                )
            },
            parse = { text, r ->
                val o = parseJson(text) ?: return@run Answer(r?.restore(text) ?: text, hits.take(3))
                val answer = o["answer"]?.jsonPrimitive?.contentOrNull?.let { r?.restore(it) ?: it } ?: return@run null
                val sources = o["sources"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }?.mapNotNull { hits.getOrNull(it - 1) }.orEmpty()
                Answer(answer, sources)
            },
        )
    }

    suspend fun extract(h: MessageHeader, body: MessageBody?): AiResult<Extraction>? = intelligence.run(
        AiTask.EXTRACT, sensitivity(h),
        build = { engine, r ->
            LlmRequest(
                AiTask.EXTRACT,
                "Find events (meetings, flights, reservations, deadlines with a time) and tasks the reader is asked to do in this email. " +
                    "Use ISO 8601 for times when the email states them, empty string otherwise. Answer with JSON only.",
                render(h, body, engine, r, budget = minOf(engine.maxInputTokens - 600, 4000)), maxOutputTokens = 4096,
                json = JsonSpec(
                    "extraction",
                    schema(
                        "events" to arrayOf(schema("title" to str(), "start" to str(), "end" to str(), "location" to str())),
                        "tasks" to arrayOf(schema("text" to str(), "due" to str())),
                    ),
                ),
            )
        },
        parse = { text, r ->
            val o = parseJson(text) ?: return@run null
            fun s(x: JsonObject, k: String) = x[k]?.jsonPrimitive?.contentOrNull?.let { r?.restore(it) ?: it }?.ifBlank { null }
            Extraction(
                events = (o["events"] as? JsonArray).orEmpty().mapNotNull { e -> val x = e.jsonObject; s(x, "title")?.let { ExtractedEvent(it, s(x, "start"), s(x, "end"), s(x, "location")) } },
                tasks = (o["tasks"] as? JsonArray).orEmpty().mapNotNull { t -> val x = t.jsonObject; s(x, "text")?.let { ExtractedTask(it, s(x, "due")) } },
            )
        },
    )

    // ------------------------------------------------------------------------------------------
    // Listening
    // ------------------------------------------------------------------------------------------

    /** The spoken briefing behind the ear button. The template works with no model at all. */
    suspend fun briefing(greeting: String, digest: Digest, windowPhrase: String, accountPhrase: String, items: List<MessageHeader>): Pair<String, AiResult<String>?> {
        val template = templateBriefing(greeting, digest, windowPhrase, accountPhrase, items)
        val result = intelligence.run(
            AiTask.BRIEFING, Sensitivity.NONE,
            build = { _, r ->
                LlmRequest(
                    AiTask.BRIEFING,
                    "Turn these notes into a short spoken briefing (under 140 words) for someone listening on their phone. " +
                        "Plain sentences only, no lists, no symbols, no URLs, nothing that sounds odd read aloud. Lead with what needs attention.",
                    r?.redact(template) ?: template, maxOutputTokens = 4096,
                )
            },
            parse = { text, r -> (r?.restore(text) ?: text).replace(Regex("[*#_`>]"), "").trim().takeIf { it.length > 20 } },
        )
        return template to result
    }

    fun templateBriefing(greeting: String, digest: Digest, windowPhrase: String, accountPhrase: String, items: List<MessageHeader>): String = buildString {
        append(greeting).append(". ")
        if (digest.total == 0) { append("No new mail $accountPhrase $windowPhrase."); return@buildString }
        append("You've got ${digest.total} new ${if (digest.total == 1) "message" else "messages"} $accountPhrase $windowPhrase. ")
        val tabs = digest.tabs.filter { it.category != null }.take(4)
        if (tabs.isNotEmpty()) append(tabs.joinToString(", ") { "${it.count} ${it.label.lowercase()}" }).append(". ")
        for (h in items.take(6)) {
            val line = h.tldr ?: h.subject
            append(line.trimEnd('.', ' ', '…')).append(". ")
        }
    }

    companion object {
        private val ACTION_TYPES = listOf(
            "mark_read", "mark_unread", "star", "archive", "trash", "spam", "pin", "mute", "move", "label", "categorize", "snooze",
            "notify_urgent", "notify_silent", "never_notify", "forward", "summarize",
        )

        val TLDR_SYSTEM = """
            You write the one-line summary shown for an email in a mail app's list.
            Write one sentence of at most 25 words, in the third person, that starts with who sent it — a person's name, or the organisation (e.g. "Fin, Anthropic's chatbot") — and says what they want or what happened.
            If the reader is asked to do something, say what and by when. Include the one number that matters (an amount, a code, a time) when there is one.
            Plain text only: no quotes, no preamble, no emoji, no markdown.
        """.trimIndent()

        val CATEGORY_SYSTEM = """
            Sort this email into exactly one category for a mail app's tabs:
            replies (an answer in a conversation the reader started or joined), tickets (support requests, help desks, issue trackers, pull requests),
            otp (verification codes, sign-in links), travel (flights, hotels, trains, car rental, itineraries), people (personal mail from a human),
            receipts (orders, invoices, payments made), shipping (packages and deliveries), events (invitations, tickets to events, RSVPs),
            finance (bank, card, investments, bills), security (sign-in alerts, password changes), newsletters (editorial mailing lists),
            promotions (marketing and offers), social (social network activity), updates (other automated notifications), other.
            Answer with JSON: {"category": "<one of the names above>", "confidence": <0 to 1>}.
        """.trimIndent()

        val THREAD_SYSTEM = """
            Summarise this email conversation for someone catching up. Use at most five short bullet points, each starting with "• ".
            Cover: what it is about, what was decided, what is still open, and who is waiting on whom (with any deadline).
            Use names. No preamble.
        """.trimIndent()

        val QUERY_SYSTEM = """
            Turn the reader's request into a search query for their mail app. The grammar:
            words match anywhere; "quoted phrases" match exactly; -word excludes; OR between terms; parentheses group.
            Fields: from:  to:  cc:  subject:  body:  domain:  list:  label:  in:inbox|sent|drafts|archive|trash|spam|anywhere
            is:unread|read|starred|unstarred|replied|unanswered|pinned|snoozed|muted|important|person|bulk|mine
            has:attachment|image|pdf|document|spreadsheet|presentation|video|audio|archive|calendar|unsubscribe|tracker|otp
            category:replies|tickets|otp|travel|people|receipts|shipping|events|finance|security|newsletters|promotions|social|updates
            date:today|yesterday|this-week|last-week|this-month|last-month|2024|march|2024-03-15   after:2024-03-01   before:2024-04-01
            older_than:7d|2w|3m|1y   newer_than:7d   size:>5mb   attachments:>2   filename:invoice   ext:pdf   account:gmail
            Use only these fields. Prefer the fewest constraints that capture the request. Answer with JSON: {"query": "…"}.
            Examples: "unread mail from my landlord about the deposit" → {"query": "is:unread from:landlord deposit"}
            "big attachments I sent last year" → {"query": "in:sent has:attachment size:>5mb date:last-year"}
        """.trimIndent()

        val RULE_SYSTEM = """
            Turn the reader's sentence into a mail rule. "query" uses this search grammar:
            from: to: subject: domain: list: label: in: is: has: category: older_than: newer_than: size: — with - to exclude and OR to combine.
            Actions: mark_read, mark_unread, star, archive, trash, spam, pin, mute, move (value: folder name or ARCHIVE/TRASH/JUNK),
            label (value: label), categorize (value: category name), snooze (value: minutes), notify_urgent, notify_silent, never_notify,
            forward (value: address), summarize.
            Use trigger "scheduled" with interval_minutes for rules about mail getting old ("after a week", "every night"), otherwise "new_mail".
            Answer with JSON: {"name": "…", "query": "…", "trigger": "new_mail"|"scheduled", "interval_minutes": 1440, "actions": [{"type": "…", "value": "…"}]}.
        """.trimIndent()
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/**
 * Upgrades heuristic tl;drs to model-written ones for the messages a person is about to see —
 * the digest window first — so the list reads like the screenshot's on any phone with a model,
 * and degrades to the extractive line on any phone without one.
 */
class TldrUpgrader(private val store: MailStore, private val ai: MailAi, private val settings: () -> AiSettings) {

    suspend fun upgrade(ids: Collection<Long>, limit: Int = 24): Int {
        if (!settings().modelSummaries) return 0
        var done = 0
        for (h in store.headers(ids)) {
            if (done >= limit) break
            if (h.tldrSource == ClassificationSource.ON_DEVICE_MODEL || h.tldrSource == ClassificationSource.CLOUD_MODEL) continue
            if (h.category == Category.OTP) continue // the code itself is the best summary
            val body = store.body(h.id)
            val result = ai.tldr(h, body) ?: continue
            store.updateDerived(h.id, tldr = result.value, tldrSource = if (result.onDevice) ClassificationSource.ON_DEVICE_MODEL else ClassificationSource.CLOUD_MODEL)
            done++
        }
        return done
    }

    /** Ask a model when the heuristic categoriser was unsure; never overrides a person's or a rule's choice. */
    suspend fun refineCategories(ids: Collection<Long>, threshold: Float = 0.5f, limit: Int = 12): Int {
        var done = 0
        for (h in store.headers(ids)) {
            if (done >= limit) break
            if (h.categorySource != ClassificationSource.HEURISTIC || h.categoryConfidence >= threshold) continue
            val result = ai.categorize(h, store.body(h.id)) ?: continue
            val (category, confidence) = result.value
            if (confidence < 0.55f) continue
            store.updateDerived(h.id, category = category, categoryConfidence = confidence,
                categorySource = if (result.onDevice) ClassificationSource.ON_DEVICE_MODEL else ClassificationSource.CLOUD_MODEL)
            done++
        }
        return done
    }
}
