package dev.aarso.mail.ai

import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId

/** What the assistant may suggest doing. Nothing here runs by itself: the person approves each one. */
enum class ActionKind(val verb: String, val destructive: Boolean = false) {
    ARCHIVE("Archive"),
    TRASH("Move to trash", destructive = true),
    MARK_READ("Mark as read"),
    STAR("Star"),
    SNOOZE("Snooze"),
    LABEL("Label"),
    DRAFT_REPLY("Draft a reply"),
    MAKE_RULE("Make a rule"),
}

/**
 * One suggestion. [arg] carries the kind's extra: the snooze time (ISO), the label, the draft text, or
 * the rule's action; [query] is the rule's matching query.
 */
data class ProposedAction(
    val kind: ActionKind,
    val title: String,
    val why: String = "",
    val ids: List<Long> = emptyList(),
    val arg: String? = null,
    val query: String? = null,
)

sealed interface AgentEvent {
    data class Searching(val query: String, val found: Int) : AgentEvent
    data class Counting(val query: String, val count: Int) : AgentEvent
    data class Reading(val subject: String) : AgentEvent
    data class Proposed(val action: ProposedAction) : AgentEvent
    data class Engine(val label: String, val onDevice: Boolean) : AgentEvent
    data class Retry(val reason: String) : AgentEvent
}

data class AgentRun(
    val proposals: List<ProposedAction>,
    val summary: String,
    val engineLabel: String,
    val onDevice: Boolean,
    val steps: Int,
)

/** What the agent can look at. Implemented over the store and search; a fake in tests. */
interface AgentHost {
    suspend fun search(query: String, limit: Int): List<MessageHeader>
    suspend fun count(query: String): Int
    suspend fun body(id: Long): MessageBody?
    fun todayIso(): String
}

/**
 * A small agent for the home screen: given a goal ("tidy up what came in since yesterday"), it looks
 * through the mail with read-only tools — search, count, read — and collects *proposals*. It cannot
 * archive, delete, send or change anything; the person reviews the proposals and applies them (each
 * with undo), and drafts only ever open in the composer.
 *
 * Models are asked for one JSON object per turn, not for native tool calls, so the same loop works
 * on a small on-device model and on a BYOK cloud one. The router decides who runs it: on-device
 * first; if that fails or cannot produce valid steps, the person's own cloud key — only if they
 * allowed this task, and never shown mail in a protected category or account, with identifiers
 * redacted. Each engine gets a fresh run, so nothing a local model read is ever replayed to a cloud one.
 */
class MailAgent(
    private val intelligence: Intelligence,
    private val host: AgentHost,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun run(goal: String, allowCloud: Boolean = true, onEvent: (AgentEvent) -> Unit = {}): AgentRun? {
        val engines = intelligence.plan(AiTask.AGENT).filter { allowCloud || it.onDevice }
        for (engine in engines) {
            onEvent(AgentEvent.Engine(engine.label, engine.onDevice))
            val r = runWith(engine, goal, onEvent)
            if (r != null) return r
        }
        return null
    }

    private suspend fun runWith(engine: LlmEngine, goal: String, onEvent: (AgentEvent) -> Unit): AgentRun? {
        val policy = intelligence.policy()
        val redactor = if (!engine.onDevice && policy.redactBeforeCloud) Redactor() else null
        val seen = LinkedHashMap<Long, MessageHeader>()
        val proposals = ArrayList<ProposedAction>()
        val log = ArrayList<String>()
        var invalid = 0
        var summary = ""
        var steps = 0

        fun withheld(h: MessageHeader) = !engine.onDevice &&
            (h.category in policy.neverCloudCategories || h.accountId in policy.neverCloudAccounts)

        fun line(h: MessageHeader): String {
            val who = h.from?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.address } ?: "?"
            val day = Instant.ofEpochMilli(h.receivedAt).atZone(zone).toLocalDate()
            val flags = (if (!h.isSeen) "unread " else "") + (if (h.isFlagged) "starred " else "") + h.category.name.lowercase()
            return "#${h.id} | $who | $day | ${h.subject.take(70)} | $flags | ${(h.tldr ?: h.preview).replace('\n', ' ').take(90)}"
        }

        fun shown(hits: List<MessageHeader>): String {
            val ok = hits.filterNot(::withheld)
            ok.forEach { seen[it.id] = it }
            val hidden = hits.size - ok.size
            val text = ok.joinToString("\n") { line(it) } + if (hidden > 0) "\n($hidden private messages not shown to this model)" else ""
            return redactor?.redact(text) ?: text
        }

        for (step in 1..MAX_STEPS) {
            steps = step
            if (!engine.onDevice && intelligence.overBudget()) break
            val budget = (engine.maxInputTokens - 1400).coerceAtLeast(600)
            val request = LlmRequest(
                AiTask.AGENT, system(host.todayIso()), prompt(goal, log, budget),
                maxOutputTokens = 700, effort = Effort.MEDIUM,
                json = if (engine.supportsStructuredOutput) SPEC else null,
            )
            val response = try {
                engine.generate(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            intelligence.recordUsage(engine, response)
            val call = parse(response.text)
            val tool = call?.str("tool")?.lowercase()
            if (call == null || tool == null) {
                if (++invalid > 2) return null
                onEvent(AgentEvent.Retry("the model's reply wasn't a valid step"))
                log += "SYSTEM: that was not valid. Reply with exactly one JSON object using one of the tools."
                continue
            }
            when (tool) {
                "search" -> {
                    val q = call.str("query") ?: run { log += "SYSTEM: search needs a query."; null } ?: continue
                    val limit = (call.int("limit") ?: 15).coerceIn(1, 25)
                    val hits = runCatching { host.search(q, limit) }.getOrDefault(emptyList())
                    onEvent(AgentEvent.Searching(q, hits.size))
                    log += "CALL search $q"
                    log += "RESULT (${hits.size}):\n" + shown(hits).ifBlank { "nothing found" }
                }
                "count" -> {
                    val q = call.str("query") ?: run { log += "SYSTEM: count needs a query."; null } ?: continue
                    val n = runCatching { host.count(q) }.getOrDefault(0)
                    onEvent(AgentEvent.Counting(q, n))
                    log += "CALL count $q"
                    log += "RESULT: $n messages"
                }
                "read" -> {
                    val id = call.long("id")
                    val h = id?.let { seen[it] }
                    if (h == null) log += "SYSTEM: you can only read messages that a search showed you."
                    else {
                        onEvent(AgentEvent.Reading(h.subject))
                        val b = runCatching { host.body(h.id) }.getOrNull()
                        val raw = b?.text ?: b?.html?.let { HtmlText.toText(it) } ?: h.preview
                        val text = trimToTokens(HtmlText.newContent(raw), (budget / 3).coerceIn(150, 1500))
                        log += "CALL read #${h.id}"
                        log += "RESULT:\n" + (redactor?.redact(text) ?: text)
                    }
                }
                "propose" -> {
                    val action = propose(call, seen, proposals.size) { q -> runCatching { host.search(q, MAX_IDS) }.getOrDefault(emptyList()).also { r -> r.forEach { seen.putIfAbsent(it.id, it) } } }
                    when (action) {
                        is Proposal.Ok -> {
                            if (proposals.none { it.kind == action.action.kind && it.ids.toSet() == action.action.ids.toSet() && it.arg == action.action.arg && it.query == action.action.query }) {
                                proposals += action.action
                                onEvent(AgentEvent.Proposed(action.action))
                            }
                            log += "CALL propose ${action.action.kind.name.lowercase()} (${action.action.ids.size})"
                            log += "RESULT: noted"
                        }
                        is Proposal.Bad -> log += "SYSTEM: ${action.why}"
                    }
                }
                "done" -> { summary = call.str("summary").orEmpty(); break }
                else -> log += "SYSTEM: unknown tool \"$tool\"."
            }
            if (proposals.size >= MAX_PROPOSALS) break
        }
        if (summary.isBlank()) summary = if (proposals.isEmpty()) "Nothing needs doing right now." else "${proposals.size} suggestion${if (proposals.size == 1) "" else "s"}."
        return AgentRun(proposals, redactor?.restore(summary) ?: summary, engine.label, engine.onDevice, steps)
    }

    private sealed interface Proposal {
        data class Ok(val action: ProposedAction) : Proposal
        data class Bad(val why: String) : Proposal
    }

    private suspend fun propose(call: JsonObject, seen: Map<Long, MessageHeader>, have: Int, expand: suspend (String) -> List<MessageHeader>): Proposal {
        if (have >= MAX_PROPOSALS) return Proposal.Bad("enough proposals; call done.")
        val kind = when (call.str("action")?.lowercase()?.replace(' ', '_')) {
            "archive" -> ActionKind.ARCHIVE
            "trash", "delete" -> ActionKind.TRASH
            "mark_read", "read" -> ActionKind.MARK_READ
            "star" -> ActionKind.STAR
            "snooze" -> ActionKind.SNOOZE
            "label" -> ActionKind.LABEL
            "draft_reply", "reply" -> ActionKind.DRAFT_REPLY
            "make_rule", "rule" -> ActionKind.MAKE_RULE
            else -> return Proposal.Bad("action must be one of archive, trash, mark_read, star, snooze, label, draft_reply, make_rule.")
        }
        val query = call.str("query")
        var ids = call.longs("ids").filter { it in seen }.distinct()
        if (kind != ActionKind.MAKE_RULE && ids.isEmpty() && query != null) ids = expand(query).map { it.id }.distinct().take(MAX_IDS)
        val why = call.str("why").orEmpty().take(240)
        fun title(default: String) = (call.str("title") ?: default).take(100)
        return when (kind) {
            ActionKind.MAKE_RULE -> {
                val q = query ?: return Proposal.Bad("make_rule needs a query.")
                val ruleAction = call.str("rule_action")?.lowercase()?.takeIf { it in setOf("archive", "trash", "mark_read", "star") } ?: return Proposal.Bad("make_rule needs rule_action: archive, trash, mark_read or star.")
                Proposal.Ok(ProposedAction(kind, title("Always $ruleAction mail matching $q"), why, emptyList(), ruleAction, q))
            }
            ActionKind.DRAFT_REPLY -> {
                val id = ids.firstOrNull() ?: return Proposal.Bad("draft_reply needs the id of a message you were shown.")
                val text = call.str("text")?.trim().takeUnless { it.isNullOrBlank() } ?: return Proposal.Bad("draft_reply needs text.")
                Proposal.Ok(ProposedAction(kind, title("Reply to ${seen[id]?.from?.let { it.name ?: it.address } ?: "this message"}"), why, listOf(id), text.take(2000)))
            }
            else -> {
                if (ids.isEmpty()) return Proposal.Bad("${kind.name.lowercase()} needs ids from a search, or a query that matches messages.")
                val arg = when (kind) {
                    ActionKind.SNOOZE -> call.str("until") ?: return Proposal.Bad("snooze needs until, like 2026-11-23T09:00.")
                    ActionKind.LABEL -> call.str("label") ?: return Proposal.Bad("label needs a label name.")
                    else -> null
                }
                Proposal.Ok(ProposedAction(kind, title("${kind.verb} ${ids.size} message${if (ids.size == 1) "" else "s"}"), why, ids, arg))
            }
        }
    }

    // ---- the model's words ----

    private fun system(today: String) = buildString {
        append("You are the assistant inside an email app. You help the owner tidy and act on their mail. ")
        append("You only have the tools below and you never send mail; everything you propose is shown to the owner to approve. ")
        append("Reply with exactly ONE JSON object per turn and nothing else.\n")
        append("Tools:\n")
        append("{\"tool\":\"search\",\"query\":\"…\",\"limit\":15}  find messages\n")
        append("{\"tool\":\"count\",\"query\":\"…\"}  how many match\n")
        append("{\"tool\":\"read\",\"id\":123}  read one message from a search result\n")
        append("{\"tool\":\"propose\",\"action\":\"archive|trash|mark_read|star|snooze|label|draft_reply|make_rule\",\"ids\":[1,2] or \"query\":\"…\",\"title\":\"short\",\"why\":\"one sentence\",\"until\":\"2026-11-23T09:00\",\"label\":\"…\",\"text\":\"reply text\",\"rule_action\":\"archive\"}\n")
        append("{\"tool\":\"done\",\"summary\":\"one or two sentences for the owner\"}\n")
        append("Query words: from:name to:name subject:word is:unread is:starred has:attachment in:inbox older_than:7d newer_than:2d category:(")
        append(Category.entries.joinToString("|") { it.name.lowercase() })
        append(") -word to exclude. Today is $today.\n")
        append("Look before you propose. Prefer a few useful proposals over many. Trash only what is clearly junk. Drafts are short, polite, in the owner's voice, and only for messages that ask something. Never invent ids.")
    }

    private fun prompt(goal: String, log: List<String>, budget: Int): String {
        val head = "Goal: $goal\n\n"
        var used = estimateTokens(head)
        val kept = ArrayList<String>()
        for (entry in log.asReversed()) {
            val t = estimateTokens(entry)
            if (used + t > budget && kept.isNotEmpty()) break
            kept += if (used + t > budget) trimToTokens(entry, (budget - used).coerceAtLeast(50)) else entry
            used += t
        }
        return head + kept.asReversed().joinToString("\n") + "\n\nYour next step (one JSON object):"
    }

    /** The first balanced JSON object in [text], tolerating code fences and chatter around it. */
    internal fun parse(text: String): JsonObject? {
        var i = text.indexOf('{')
        while (i >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            for (j in i until text.length) {
                val c = text[j]
                if (inString) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                } else when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) {
                        runCatching { json.parseToJsonElement(text.substring(i, j + 1)).jsonObject }.getOrNull()?.let { return it }
                        break
                    }
                }
            }
            i = text.indexOf('{', i + 1)
        }
        return null
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }?.takeIf { it != 0 }
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim('#')?.toLongOrNull() }?.takeIf { it != 0L }
    private fun JsonObject.longs(k: String): List<Long> = (this[k] as? JsonArray)?.mapNotNull { e: JsonElement -> (e as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim('#')?.toLongOrNull() } }.orEmpty()
        .ifEmpty { listOfNotNull(long("id")) }

    companion object {
        const val MAX_STEPS = 9
        const val MAX_PROPOSALS = 12
        const val MAX_IDS = 200

        private fun s() = buildJsonObject { put("type", "string") }
        private fun n() = buildJsonObject { put("type", "integer") }
        private val SPEC = JsonSpec("agent_step", buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("tool", buildJsonObject { put("type", "string"); putJsonArray("enum") { listOf("search", "count", "read", "propose", "done").forEach { add(JsonPrimitive(it)) } } })
                for (k in listOf("query", "action", "title", "why", "until", "label", "text", "rule_action", "summary")) put(k, s())
                for (k in listOf("limit", "id")) put(k, n())
                put("ids", buildJsonObject { put("type", "array"); put("items", n()) })
            })
            putJsonArray("required") { listOf("tool", "query", "action", "title", "why", "until", "label", "text", "rule_action", "summary", "limit", "id", "ids").forEach { add(JsonPrimitive(it)) } }
        })
    }
}
