package dev.aarso.mail.intel

import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.MessageHeader

/**
 * The tl;dr that needs no model: a one-line, extractive summary built from the message's own words,
 * shaped by what the message is. For a code it is the code; for a parcel, where it is; for a person,
 * the sentence that carries the ask. The on-device model (Gemini Nano) and the BYOK cloud models
 * replace this with an abstractive line when they are available — this is the floor, and it is
 * never empty.
 */
object Summarizer {

    private val GREETING = Regex("(?i)^(hi|hello|hey|dear|good (morning|afternoon|evening)|greetings|hallo|bonjour|hola|ciao|namaste)\\b[^.!?\\n]{0,40}[,!.:]?\\s*")
    private val BOILERPLATE = Regex(
        "(?i)(view (it )?in (your )?browser|unsubscribe|no longer wish|manage (your )?preferences|privacy policy|terms of (service|use)|" +
            "all rights reserved|©|copyright|this email was sent|you are receiving|you received this|add us to your address book|" +
            "do not reply|please do not reply|download (the|our) app|follow us|click here|read more|learn more|sent from my|confidential|" +
            "if you (did not|didn't) (request|sign)|having trouble viewing)",
    )
    private val SIGNOFF = Regex("(?i)^(thanks|thank you|best|regards|kind regards|cheers|sincerely|warmly|talk soon|br|thx)\\b")
    private val ASK = Regex("(?i)\\b(please|can you|could you|would you|let me know|need|required|asap|deadline|by (mon|tue|wed|thu|fri|sat|sun|tomorrow|today|end of)|reply|confirm|approve|review|sign)\\b")
    private val INFO = Regex("[$€£¥₹]\\s?\\d|\\d{1,2}:\\d{2}|\\b\\d{1,2}(st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)|\\b(today|tomorrow|tonight|monday|tuesday|wednesday|thursday|friday)\\b", RegexOption.IGNORE_CASE)

    fun summarize(h: MessageHeader, text: String?, category: Category, otp: OtpFinding?, cards: List<StructuredCard>): String {
        val sender = h.from?.shortName()?.replaceFirstChar { it.uppercase() } ?: "Someone"
        val org = h.from?.name?.takeIf { it.isNotBlank() } ?: h.from?.domain?.substringBeforeLast('.')?.replaceFirstChar { it.uppercase() } ?: sender

        cards.firstOrNull()?.let { c ->
            val detail = c.fields["departs"] ?: c.fields["check-in"] ?: c.fields["arrives"] ?: c.fields["starts"] ?: c.fields["total"]
            return clip(listOfNotNull(c.title, detail?.let { prettyTime(it) }).joinToString(" — "))
        }
        if (category == Category.OTP && otp != null) {
            return when {
                otp.code != null -> "${otp.service ?: org} code ${otp.code}" + (otp.validMinutes?.let { ", valid $it min" } ?: "")
                else -> "Sign-in link from ${otp.service ?: org}"
            }
        }

        val content = HtmlText.newContent(text.orEmpty()).ifBlank { h.preview }
        val best = bestSentence(content, h.subject)
        val subject = h.subject.replace(Regex("(?i)^((re|fwd?|aw|wg|tr|sv|vs)\\s*:\\s*)+"), "").trim()
        val line = when {
            best == null && subject.isNotEmpty() -> "$org: $subject"
            best == null -> org
            category == Category.PEOPLE || category == Category.REPLIES -> "$sender: $best"
            else -> "$org: $best"
        }
        return clip(line)
    }

    /** The sentence most worth a glance: early, on-topic, carrying an ask or a concrete fact. */
    fun bestSentence(text: String, subject: String): String? {
        val subjectWords = subject.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        val sentences = sentences(text).take(14)
        var best: Pair<String, Double>? = null
        for ((i, raw) in sentences.withIndex()) {
            val s = GREETING.replace(raw.trim(), "").trim()
            if (s.length < 18 || s.length > 260) continue
            if (BOILERPLATE.containsMatchIn(s) || SIGNOFF.containsMatchIn(s)) continue
            if (s.count { it.isLetter() } < s.length / 2) continue
            var score = 1.0 / (1 + i * 0.35)
            val words = s.lowercase().split(Regex("\\W+")).toSet()
            score += 0.35 * (words intersect subjectWords).size.coerceAtMost(3)
            if (ASK.containsMatchIn(s)) score += 0.6
            if (INFO.containsMatchIn(s)) score += 0.35
            if (s.endsWith("?")) score += 0.25
            if (best == null || score > best.second) best = s to score
        }
        return best?.first?.trimEnd('.', ' ') ?: sentences.firstOrNull { it.length >= 12 }?.trim()
    }

    fun sentences(text: String): List<String> =
        text.replace(Regex("\\s*\\n\\s*\\n\\s*"), "\n").split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"“(])|\\n"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }

    private fun clip(s: String, max: Int = 150): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val cut = t.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        return t.substring(0, cut).trimEnd(',', ';', ':', '-', '—') + "…"
    }

    private fun prettyTime(iso: String): String = runCatching {
        val t = java.time.OffsetDateTime.parse(iso)
        java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").format(t)
    }.getOrElse { iso.replace('T', ' ').take(16) }
}
