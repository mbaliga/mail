package dev.aarso.mail.intel

/** A one-time code or sign-in link found in a message, with when it stops being useful. */
data class OtpFinding(
    val code: String?,
    val isMagicLink: Boolean,
    val service: String?,
    /** Minutes the sender says it is valid for; null when unstated (the UI assumes 15). */
    val validMinutes: Int?,
    val confidence: Float,
)

/**
 * Finds verification codes the way a person scans for them: a 4–8 digit (or short alphanumeric)
 * token *near* a word that means "code" in one of the languages mail arrives in — and not a year, a
 * price, a phone number, a time or an order number that merely looks like one.
 */
object OtpExtractor {

    private val KEYWORDS = listOf(
        "verification code", "verify", "one-time", "one time", "otp", "passcode", "pass code", "security code", "login code",
        "log-in code", "sign-in code", "sign in code", "authentication code", "confirmation code", "access code", "2fa", "two-factor",
        "two factor", "2-step", "your code", "the code", "code is", "code:", "pin", "auth code", "temporary code",
        "bestätigungscode", "sicherheitscode", "verifizierungscode", "code de vérification", "code de sécurité", "code de confirmation",
        "código de verificación", "código de seguridad", "código", "codice di verifica", "codice", "verificatiecode",
        "kod weryfikacyjny", "код подтверждения", "код", "验证码", "認証コード", "確認コード", "인증번호", "인증 코드", "doğrulama kodu",
    )

    private val MAGIC = Regex(
        "(?i)(magic link|sign[- ]?in link|log[- ]?in link|click (the )?(link|button) (below )?to (sign|log) ?in|confirm your (sign[- ]?in|login)|one-click sign[- ]?in)",
    )

    /** Spaced or dashed digit groups count: "123 456", "123-456". */
    /** A trailing full stop ends a sentence, so it is allowed; a full stop *followed by a digit* is a decimal. */
    private val CANDIDATE = Regex("(?<![\\w$€£¥₹/.:#-])(G-)?(\\d{3,4}[ -]\\d{3,4}|\\d{4,8}|[A-Z0-9]{6,8})(?![\\w%/:-]|\\.\\d)")

    private val VALIDITY = Regex("(?i)(?:valid|expires?|expiring|good)\\s+(?:for|in|within)\\s+(\\d{1,3})\\s*(min|minute|minutes|mins|hour|hours|h)\\b")

    fun find(subject: String, text: String, fromName: String?): OtpFinding? {
        val hay = "$subject\n" + text.take(4000)
        val lower = hay.lowercase()
        val keywordPositions = KEYWORDS.flatMap { k -> Regex(Regex.escape(k)).findAll(lower).map { it.range.first } }.sorted()
        val magic = MAGIC.containsMatchIn(hay)
        if (keywordPositions.isEmpty() && !magic) return null

        var best: Pair<String, Float>? = null
        for (m in CANDIDATE.findAll(hay)) {
            val raw = m.value
            val token = raw.removePrefix("G-").replace(" ", "").replace("-", "")
            if (!plausible(token, hay, m.range.first)) continue
            val distance = keywordPositions.minOfOrNull { kotlin.math.abs(it - m.range.first) } ?: 9999
            if (distance > 160) continue
            var score = 1f - distance / 200f
            if (m.range.first < subject.length) score += 0.35f // codes in the subject line are almost always the code
            if (token.all { it.isDigit() }) score += 0.15f
            if (token.length == 6) score += 0.1f
            // A line that is nothing but the code.
            val lineStart = hay.lastIndexOf('\n', m.range.first) + 1
            val lineEnd = hay.indexOf('\n', m.range.last).let { if (it < 0) hay.length else it }
            if (hay.substring(lineStart, lineEnd).trim() == raw.trim()) score += 0.3f
            if (best == null || score > best.second) best = raw.trim() to score
        }
        val validity = VALIDITY.find(hay)?.let { v ->
            val n = v.groupValues[1].toIntOrNull() ?: return@let null
            if (v.groupValues[2].startsWith("h")) n * 60 else n
        }
        if (best == null && !magic) return null
        return OtpFinding(
            code = best?.first,
            isMagicLink = best == null && magic,
            service = fromName?.substringBefore(" <")?.trim()?.takeIf { it.isNotEmpty() },
            validMinutes = validity,
            confidence = (best?.second ?: 0.6f).coerceIn(0f, 1f),
        )
    }

    private fun plausible(token: String, hay: String, at: Int): Boolean {
        if (token.length < 4 || token.length > 8) return false
        val digits = token.count { it.isDigit() }
        if (token.any { it.isLetter() }) {
            // Alphanumeric codes must mix letters and digits and be upper-case; words are not codes.
            if (digits == 0 || token.any { it.isLowerCase() }) return false
        }
        if (token.all { it.isDigit() }) {
            val n = token.toLongOrNull() ?: return false
            if (token.length == 4 && n in 1900..2099) return false // a year
            if (token.toSet().size == 1 && token.length < 6) return false // 0000, 1111
        }
        val before = hay.substring(maxOf(0, at - 12), at).lowercase()
        if (before.contains("order") || before.contains("invoice") || before.contains("#") || before.contains("tel") ||
            before.contains("phone") || before.contains("ref") || before.contains("account ending") || before.contains("card ending")
        ) return false
        return true
    }
}
