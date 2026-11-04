package dev.aarso.mail.search

import dev.aarso.mail.model.Category
import dev.aarso.search.NaturalVocabulary

/**
 * Mail-specific natural language, rewritten into the structured grammar *before* search-core's own
 * [dev.aarso.search.NaturalQuery] pass (which handles dates, sizes and kinds). After both passes a
 * spoken or typed "unread newsletters from substack older than 30 days" is exactly the query
 * `is:unread category:newsletters from:substack older_than:30d` — same chips, same diagnostics, same
 * round trip, so a person can see and edit what their words became.
 */
object MailNatural {

    /** search-core's kind nouns → our `type:` (alias of `has:`) values. */
    val vocabulary = NaturalVocabulary(
        mapOf(
            "photos" to "image", "images" to "image", "pictures" to "image", "pics" to "image", "screenshots" to "image",
            "pdfs" to "pdf", "documents" to "document", "docs" to "document", "spreadsheets" to "spreadsheet", "sheets" to "spreadsheet",
            "slides" to "presentation", "presentations" to "presentation", "decks" to "presentation", "videos" to "video",
            "recordings" to "audio", "voicemails" to "audio", "zips" to "archive", "archives" to "archive",
            "invites" to "calendar", "invitations" to "calendar",
        ),
    )

    private val CATEGORY_WORDS: Map<String, Category> = buildMap {
        Category.entries.forEach { put(it.key, it); put(it.label.lowercase(), it) }
        put("codes", Category.OTP); put("otps", Category.OTP); put("verification", Category.OTP)
        put("packages", Category.SHIPPING); put("deliveries", Category.SHIPPING); put("parcels", Category.SHIPPING)
        put("flights", Category.TRAVEL); put("trips", Category.TRAVEL); put("bookings", Category.TRAVEL)
        put("orders", Category.RECEIPTS); put("invoices", Category.RECEIPTS); put("bills", Category.FINANCE)
        put("statements", Category.FINANCE); put("newsletter", Category.NEWSLETTERS); put("promos", Category.PROMOTIONS)
        put("marketing", Category.PROMOTIONS); put("ads", Category.PROMOTIONS); put("notifications", Category.UPDATES)
        put("alerts", Category.SECURITY); put("support", Category.TICKETS); put("issues", Category.TICKETS)
    }

    private val STATE_WORDS = mapOf(
        "unread" to "is:unread", "unopened" to "is:unread", "starred" to "is:starred", "flagged" to "is:starred",
        "important" to "is:important", "pinned" to "is:pinned", "snoozed" to "is:snoozed", "muted" to "is:muted",
        "unanswered" to "is:unanswered", "replied" to "is:replied", "drafts" to "in:drafts", "sent" to "in:sent",
        "trash" to "in:trash", "spam" to "in:spam", "archived" to "in:archive", "tracked" to "is:tracked",
    )

    private val MONTHS = setOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val DATEISH = MONTHS + setOf("today", "yesterday", "last", "this")
    private val STOP = setOf("all", "my", "the", "mails", "emails", "messages", "mail", "email", "show", "me", "find", "get", "any")

    fun rewrite(text: String): String {
        val words = tokenize(text)
        if (words.isEmpty()) return text
        val out = ArrayList<String>()
        var changed = false
        var i = 0
        fun w(k: Int) = words.getOrNull(k)?.lowercase()
        while (i < words.size) {
            val word = words[i]
            val lw = word.lowercase()
            if (word.contains(':') || word.startsWith("\"") || word.startsWith("(") || word.startsWith("-") || word.startsWith("/")) {
                out += word; i++; continue
            }
            when {
                (lw == "from" || lw == "by") && w(i + 1) != null && w(i + 1)!! !in DATEISH && !w(i + 1)!!.matches(Regex("\\d{4}")) -> {
                    out += "from:" + quote(words[i + 1]); i += 2; changed = true
                }
                (lw == "to" || lw == "for") && w(i + 1) != null && w(i + 1)!! !in STOP && w(i + 1)!! !in DATEISH -> {
                    out += "to:" + quote(words[i + 1]); i += 2; changed = true
                }
                lw == "with" && (w(i + 1) == "attachments" || w(i + 1) == "attachment" || w(i + 1) == "files") -> {
                    out += "has:attachment"; i += 2; changed = true
                }
                lw == "with" && w(i + 1) == "an" && w(i + 2) == "attachment" -> { out += "has:attachment"; i += 3; changed = true }
                (lw == "older" || lw == "newer") && w(i + 1) == "than" && w(i + 2)?.toIntOrNull() != null -> {
                    val unit = unitOf(w(i + 3))
                    if (unit != null) {
                        out += (if (lw == "older") "older_than:" else "newer_than:") + w(i + 2) + unit; i += 4; changed = true
                    } else { out += word; i++ }
                }
                (lw == "in" || lw == "within") && w(i + 1) == "the" && w(i + 2) == "last" && w(i + 3)?.toIntOrNull() != null && unitOf(w(i + 4)) != null -> {
                    out += "newer_than:" + w(i + 3) + unitOf(w(i + 4)); i += 5; changed = true
                }
                lw == "in" && w(i + 1) in setOf("inbox", "sent", "drafts", "trash", "spam", "junk", "archive") -> {
                    out += "in:" + w(i + 1); i += 2; changed = true
                }
                lw == "about" && w(i + 1) != null -> { i++; changed = true }
                lw in STATE_WORDS -> { out += STATE_WORDS.getValue(lw); i++; changed = true }
                lw in CATEGORY_WORDS -> { out += "category:" + CATEGORY_WORDS.getValue(lw).key; i++; changed = true }
                lw in setOf("unsubscribable", "lists", "mailing") && (lw != "mailing" || w(i + 1) == "lists") -> {
                    out += "has:unsubscribe"; i += if (lw == "mailing") 2 else 1; changed = true
                }
                lw in STOP && out.isEmpty() -> { i++; changed = true }
                else -> { out += word; i++ }
            }
        }
        return if (changed) out.joinToString(" ") else text
    }

    private fun unitOf(w: String?): String? = when (w?.trimEnd('s')) {
        "hour" -> "h"; "day" -> "d"; "week" -> "w"; "month" -> "m"; "year" -> "y"; else -> null
    }

    private fun quote(s: String) = if (s.any { it.isWhitespace() }) "\"$s\"" else s

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (c in text) {
            when {
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c.isWhitespace() && !inQuote -> { if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) } }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
