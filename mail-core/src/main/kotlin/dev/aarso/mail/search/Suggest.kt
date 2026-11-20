package dev.aarso.mail.search

import dev.aarso.mail.discovery.Providers
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.store.MailStore

/** What the search box offers while you type: a replacement for the token under the cursor. */
data class Suggestion(
    /** Where the token starts in the text; the suggestion replaces `[start, end)`. */
    val start: Int,
    val end: Int,
    val insert: String,
    val label: String,
    val detail: String? = null,
    val kind: Kind,
) {
    enum class Kind { OPERATOR, PERSON, FOLDER, VALUE, ACCOUNT }

    fun apply(text: String): String = text.substring(0, start) + insert + text.substring(end)
}

/**
 * Typeahead for the query language: operators as you start a word (`fr` → `from:`), then values for
 * the operator you are on (`from:pri` → the people you actually correspond with, `in:` → your
 * folders, `category:` → the tabs, `is:` / `has:` → their fixed words, `date:` → the usual spans).
 * A bare word of two letters or more also offers matching people, since "mail from Priya" is the
 * commonest search of all.
 */
class SearchSuggest(private val store: MailStore, private val search: MailSearch) {

    private val operators: List<Pair<String, Set<String>>> by lazy {
        // Registry keys, with the short aliases people type (`l:`, `cat:`) offered under their full name.
        search.fields.registry.keys.map { k -> k to (search.fields.registry[k]?.aliases ?: emptySet()) }
    }

    suspend fun suggest(text: String, caret: Int = text.length, limit: Int = 8): List<Suggestion> {
        val c = caret.coerceIn(0, text.length)
        // The token under the caret: from the last unquoted space before it.
        var start = c
        while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] != '(') start--
        val token = text.substring(start, c)
        if (token.isEmpty() || token.count { it == '"' } % 2 == 1) return emptyList()
        val negated = token.startsWith("-")
        val body = token.removePrefix("-")
        val prefix = if (negated) "-" else ""
        val colon = body.indexOf(':')
        return if (colon < 0) bare(body, start, c, prefix, limit) else values(body.substring(0, colon).lowercase(), body.substring(colon + 1), start, c, prefix, limit)
    }

    private suspend fun bare(word: String, start: Int, end: Int, prefix: String, limit: Int): List<Suggestion> {
        if (word.isEmpty()) return emptyList()
        val w = word.lowercase()
        val ops = operators.filter { (k, aliases) -> k.startsWith(w) || aliases.any { it.startsWith(w) } }
            .map { (k, _) -> Suggestion(start, end, "$prefix$k:", "$k:", operatorHelp[k], Suggestion.Kind.OPERATOR) }
        val people = if (w.length >= 2) store.suggestContacts(w, 4).map {
            Suggestion(start, end, "${prefix}from:${it.address}", it.name ?: it.address, "from: ${it.address}", Suggestion.Kind.PERSON)
        } else emptyList()
        return (ops.take(4) + people + ops.drop(4)).take(limit)
    }

    private suspend fun values(key: String, value: String, start: Int, end: Int, prefix: String, limit: Int): List<Suggestion> {
        val v = value.trim('"').lowercase()
        fun s(insert: String, label: String, detail: String? = null, kind: Suggestion.Kind = Suggestion.Kind.VALUE) =
            Suggestion(start, end, "$prefix$key:$insert", label, detail, kind)
        fun List<String>.starting() = filter { it.lowercase().startsWith(v) || (v.length >= 2 && it.lowercase().contains(v)) }
        return when (key) {
            "from", "to", "cc", "bcc" ->
                store.suggestContacts(v.ifEmpty { "" }, limit).map { s(it.address, it.name ?: it.address, it.address, Suggestion.Kind.PERSON) }
            "domain" -> store.suggestContacts(v, 30).map { it.address.substringAfter('@') }.distinct().starting().take(limit).map { s(it, it) }
            "in", "folder", "mailbox", "box" -> {
                val roles = listOf("inbox", "sent", "drafts", "archive", "trash", "spam", "starred", "snoozed", "important", "anywhere")
                val names = store.mailboxes().filter { it.role == MailboxRole.NONE && it.selectable }.map { it.name }.distinct()
                (roles + names).starting().take(limit).map { s(quoteIfNeeded(it), it, null, Suggestion.Kind.FOLDER) }
            }
            "label", "l", "tag" -> store.mailboxes().filter { it.role == MailboxRole.NONE }.map { it.name }.distinct().starting().take(limit).map { s(quoteIfNeeded(it), it, null, Suggestion.Kind.FOLDER) }
            "category", "cat", "tab" -> Category.entries.map { it.key }.starting().take(limit).map { s(it, Category.fromKey(it)?.label ?: it) }
            "account", "acct", "on" -> {
                val accounts = store.accounts()
                accounts.flatMap { listOfNotNull(it.email, Providers.byId(it.providerId)?.name?.lowercase()) }.distinct().starting().take(limit)
                    .map { s(it, it, null, Suggestion.Kind.ACCOUNT) }
            }
            "is" -> listOf("unread", "read", "starred", "unstarred", "replied", "unanswered", "pinned", "snoozed", "muted", "important", "person", "bulk", "mine").starting().take(limit).map { s(it, it) }
            "has" -> listOf("attachment", "image", "pdf", "document", "spreadsheet", "presentation", "video", "audio", "archive", "calendar", "unsubscribe", "tracker", "otp").starting().take(limit).map { s(it, it) }
            "date", "after", "before" -> listOf("today", "yesterday", "this-week", "last-week", "this-month", "last-month", "this-year", "last-year").starting().take(limit).map { s(it, it) }
            "older_than", "newer_than" -> listOf("1d", "7d", "2w", "1m", "3m", "6m", "1y").starting().take(limit).map { s(it, it.replace("d", " days").replace("w", " weeks").replace("m", " months").replace("y", " year")) }
            "size", "larger", "smaller" -> listOf(">1mb", ">5mb", ">10mb", ">25mb", "<100kb").starting().take(limit).map { s(it, it) }
            "ext" -> listOf("pdf", "docx", "xlsx", "pptx", "zip", "png", "jpg", "ics", "txt").starting().take(limit).map { s(it, it) }
            else -> emptyList()
        }
    }

    private fun quoteIfNeeded(s: String) = if (s.any { it.isWhitespace() }) "\"$s\"" else s

    private val operatorHelp = mapOf(
        "from" to "sender", "to" to "recipient", "cc" to "copied", "subject" to "in the subject", "body" to "in the text",
        "in" to "folder", "label" to "label", "is" to "state", "has" to "contains", "category" to "tab", "date" to "when",
        "after" to "since", "before" to "until", "older_than" to "age", "newer_than" to "age", "size" to "bigger / smaller",
        "filename" to "attachment name", "ext" to "file type", "account" to "which account", "domain" to "company",
    )
}
