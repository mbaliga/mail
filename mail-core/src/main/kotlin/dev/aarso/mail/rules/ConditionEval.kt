package dev.aarso.mail.rules

import dev.aarso.mail.model.Condition
import dev.aarso.mail.model.HeaderOp
import dev.aarso.mail.model.MessageHeader
import java.time.ZoneId

/** Decides whether a message satisfies a search-grammar query, without touching a database. Provided by `MailSearch.queryMatcher()`. */
fun interface QueryMatcher {
    fun matches(query: String, message: MessageHeader): Boolean
}

/** Everything a pure evaluation may depend on, passed in so a test can pin it. */
class RuleContext(
    val matcher: QueryMatcher,
    val now: Long = System.currentTimeMillis(),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Is this (normalised) address someone the person has written to or received from? */
    val knownContact: (String) -> Boolean = { false },
)

/**
 * The tree's meaning for one message. Pure: the same message, condition and context always give the same answer.
 *
 * Full-text terms inside a [Condition.Query] are checked against what a header carries (subject, sender, the
 * preview), which is all that exists the moment mail arrives; the batch paths in [RuleEngine] also use the full-text
 * index, so a rule can match on body words there that this function cannot see.
 */
object ConditionEval {

    fun matches(c: Condition, h: MessageHeader, ctx: RuleContext): Boolean = when (c) {
        is Condition.All -> c.of.all { matches(it, h, ctx) }
        is Condition.AnyOf -> c.of.any { matches(it, h, ctx) }
        is Condition.Not -> !matches(c.of, h, ctx)
        is Condition.Query -> c.q.isBlank() || ctx.matcher.matches(c.q, h)
        is Condition.Header -> header(c, h)
        is Condition.AgeOver -> ctx.now - h.receivedAt > c.minutes * 60_000L
        is Condition.SizeOver -> h.size > c.bytes
        is Condition.ListId -> h.listId?.contains(c.value, ignoreCase = true) == true
        Condition.IsReply -> !h.inReplyTo.isNullOrBlank() || REPLY_PREFIX.containsMatchIn(h.subject)
        Condition.FromKnownContact -> h.from?.normalized?.let(ctx.knownContact) == true
        is Condition.InCategory -> h.category == c.category
    }

    /** The headers the store keeps, by lower-case name. Null for any other header: it was not kept, so no rule can see it. */
    fun headerValues(h: MessageHeader, name: String): List<String>? {
        fun addrs(l: List<dev.aarso.mail.model.EmailAddress>) = l.map { a -> a.name?.takeIf { it.isNotBlank() }?.let { n -> "$n <${a.address}>" } ?: a.address }
        return when (name.trim().lowercase()) {
            "from" -> listOfNotNull(h.from).let(::addrs)
            "to" -> addrs(h.to)
            "cc" -> addrs(h.cc)
            "bcc" -> addrs(h.bcc)
            "reply-to" -> addrs(h.replyTo)
            "sender" -> listOfNotNull(h.sender).let(::addrs)
            "subject" -> listOf(h.subject)
            "list-id" -> listOfNotNull(h.listId)
            "list-unsubscribe" -> listOfNotNull(h.listUnsubscribe)
            "message-id" -> listOfNotNull(h.messageId)
            "in-reply-to" -> listOfNotNull(h.inReplyTo)
            "references" -> listOf(h.references.joinToString(" ")).filter { it.isNotBlank() }
            "authentication-results" -> listOfNotNull(h.authResults)
            else -> null
        }
    }

    /** Header names a rule can test. */
    val KNOWN_HEADERS = listOf("from", "to", "cc", "bcc", "reply-to", "sender", "subject", "list-id", "list-unsubscribe", "message-id", "in-reply-to", "references", "authentication-results")

    private fun header(c: Condition.Header, h: MessageHeader): Boolean {
        val values = headerValues(h, c.name) ?: return false
        return when (c.op) {
            HeaderOp.EXISTS -> values.any { it.isNotBlank() }
            HeaderOp.CONTAINS -> values.any { it.contains(c.value, ignoreCase = true) }
            HeaderOp.EQUALS -> values.any { it.equals(c.value, ignoreCase = true) || bareAddress(it).equals(c.value, ignoreCase = true) }
            HeaderOp.REGEX -> runCatching { Regex(c.value, RegexOption.IGNORE_CASE) }.getOrNull()?.let { rx -> values.any { rx.containsMatchIn(it) } } == true
        }
    }

    private fun bareAddress(v: String) = v.substringAfter('<', v).substringBefore('>').trim()

    private val REPLY_PREFIX = Regex("^\\s*(re|aw|sv|antw|vs|odp|res|rv)\\s*(\\[\\d+])?\\s*:", RegexOption.IGNORE_CASE)
}
