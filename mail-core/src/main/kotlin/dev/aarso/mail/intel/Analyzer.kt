package dev.aarso.mail.intel

import dev.aarso.mail.mime.HtmlText
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.ClassificationSource
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader

/** Everything derived from one message, with where each fact came from. */
data class Analysis(
    val category: Category,
    val categoryConfidence: Float,
    val categorySource: ClassificationSource,
    val reasons: List<String>,
    val tldr: String,
    val tldrSource: ClassificationSource,
    val otp: OtpFinding?,
    val cards: List<StructuredCard>,
    val language: String?,
    val priority: Float,
    val trackerCount: Int,
)

/**
 * The seam between sync and intelligence. :mail-core ships [HeuristicAnalyzer]; :mail-ai wraps it
 * with on-device models (Gemini Nano, a local Gemma file) and the user's own cloud keys, keeping the
 * heuristic answer as the floor and the fallback.
 */
interface MessageAnalyzer {
    suspend fun analyze(header: MessageHeader, body: MessageBody?, context: MessageContext): Analysis
}

/** Instant, deterministic, on-device, no model. Runs for every message on every phone. */
class HeuristicAnalyzer : MessageAnalyzer {

    override suspend fun analyze(header: MessageHeader, body: MessageBody?, context: MessageContext): Analysis {
        val text = body?.text ?: body?.html?.let { HtmlText.toText(it) } ?: header.preview
        val cards = body?.structuredData?.let { StructuredData.cards(it) }.orEmpty()
        val otp = OtpExtractor.find(header.subject, text, header.from?.name)
        val verdict = Categorizer.classify(header, text, context, otp, cards)
        val tldr = Summarizer.summarize(header, text, verdict.category, otp, cards)
        return Analysis(
            category = verdict.category,
            categoryConfidence = verdict.confidence,
            categorySource = ClassificationSource.HEURISTIC,
            reasons = verdict.reasons,
            tldr = tldr,
            tldrSource = ClassificationSource.HEURISTIC,
            otp = otp,
            cards = cards,
            language = LanguageGuess.guess(text),
            priority = Priority.score(header, verdict.category, context, otp),
            trackerCount = body?.html?.let { HtmlText.trackerCount(it) } ?: 0,
        )
    }
}

/** 0..1 "how much does this deserve attention", for notification policy and ordering. */
object Priority {
    fun score(h: MessageHeader, category: Category, ctx: MessageContext, otp: OtpFinding?): Float {
        var p = when (category) {
            Category.OTP -> 0.9f
            Category.REPLIES -> 0.8f
            Category.PEOPLE -> 0.7f
            Category.SECURITY -> 0.75f
            Category.TICKETS -> 0.55f
            Category.TRAVEL -> 0.6f
            Category.FINANCE -> 0.5f
            Category.EVENTS -> 0.5f
            Category.SHIPPING -> 0.4f
            Category.RECEIPTS -> 0.3f
            Category.UPDATES -> 0.25f
            Category.SOCIAL -> 0.2f
            Category.NEWSLETTERS -> 0.15f
            Category.PROMOTIONS -> 0.05f
            Category.OTHER -> 0.3f
        }
        if (ctx.knownCorrespondent) p += 0.1f
        if (ctx.directToMe && !h.isBulk) p += 0.05f
        if (h.importanceHint?.let { it.contains("high", true) || it.trim().startsWith("1") || it.trim().startsWith("2") } == true) p += 0.05f
        if (h.isFlagged) p += 0.1f
        if (otp?.code != null) p = maxOf(p, 0.9f)
        if (h.muted) p = 0f
        return p.coerceIn(0f, 1f)
    }
}

/** A cheap script/stop-word language guess, used until (or instead of) ML Kit language-id. */
object LanguageGuess {
    private val STOP = mapOf(
        "en" to setOf("the", "and", "you", "your", "for", "with", "this", "that", "are", "have"),
        "de" to setOf("und", "der", "die", "das", "sie", "ist", "nicht", "mit", "für", "ihre"),
        "fr" to setOf("le", "la", "les", "et", "vous", "est", "pour", "avec", "votre", "des"),
        "es" to setOf("el", "la", "los", "y", "usted", "es", "para", "con", "su", "que"),
        "it" to setOf("il", "la", "e", "di", "che", "per", "con", "sono", "del", "una"),
        "nl" to setOf("de", "het", "en", "een", "van", "je", "voor", "met", "uw", "is"),
        "pt" to setOf("o", "a", "os", "e", "você", "para", "com", "seu", "que", "não"),
    )

    fun guess(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val sample = text.take(1500)
        var han = 0; var kana = 0; var hangul = 0; var cyr = 0; var arab = 0; var deva = 0; var latin = 0
        for (c in sample) {
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.CYRILLIC -> cyr++
                Character.UnicodeScript.ARABIC -> arab++
                Character.UnicodeScript.DEVANAGARI -> deva++
                Character.UnicodeScript.LATIN -> latin++
                else -> {}
            }
        }
        val total = maxOf(1, han + kana + hangul + cyr + arab + deva + latin)
        return when {
            kana > total / 10 -> "ja"
            hangul > total / 5 -> "ko"
            han > total / 5 -> "zh"
            cyr > total / 3 -> "ru"
            arab > total / 3 -> "ar"
            deva > total / 3 -> "hi"
            else -> {
                val words = sample.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
                STOP.maxByOrNull { (_, stop) -> words.count { it in stop } }?.takeIf { (_, stop) -> words.count { it in stop } >= 3 }?.key
            }
        }
    }
}
