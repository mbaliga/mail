package dev.aarso.mail.ai

import dev.aarso.mail.model.Category
import kotlinx.serialization.Serializable

/**
 * Replaces what identifies people and money with placeholders before text leaves the phone, and
 * puts it back in what comes back. The model still understands "<email1> asked to move the call
 * to <phone1>"; the provider never sees the address or the number.
 *
 * Deliberately conservative about what it touches: names stay (a summary that cannot say who wrote
 * is useless), amounts stay (they are what a receipt is *about*), and everything that is an
 * identifier or a credential goes — addresses, phone numbers, card numbers (Luhn-checked), IBANs,
 * one-time codes, and the query strings of links, which is where tracking tokens and magic-link
 * secrets live.
 */
class Redactor {
    private val forward = LinkedHashMap<String, String>()
    private val counters = HashMap<String, Int>()

    private fun token(kind: String, original: String): String =
        forward.entries.firstOrNull { it.value == original }?.key ?: run {
            val n = (counters[kind] ?: 0) + 1
            counters[kind] = n
            "<$kind$n>".also { forward[it] = original }
        }

    fun redact(text: String): String {
        var s = text
        s = URL.replace(s) { m ->
            val url = m.value
            val cut = url.indexOfAny(charArrayOf('?', '#'))
            if (cut > 0) url.substring(0, cut) + "…" else url
        }
        s = EMAIL.replace(s) { token("email", it.value) }
        s = IBAN.replace(s) { token("iban", it.value) }
        s = CARD.replace(s) { m -> if (luhn(m.value.filter(Char::isDigit))) token("card", m.value) else m.value }
        s = PHONE.replace(s) { m -> if (m.value.count(Char::isDigit) in 9..15) token("phone", m.value) else m.value }
        s = CODE.replace(s) { m -> m.groupValues[1] + token("code", m.groupValues[2]) }
        return s
    }

    /** Put originals back into a model's answer (drafts that must address the real person). */
    fun restore(text: String): String {
        var s = text
        for ((k, v) in forward) s = s.replace(k, v)
        return s
    }

    val count: Int get() = forward.size

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        var alt = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (alt) { d *= 2; if (d > 9) d -= 9 }
            sum += d; alt = !alt
        }
        return sum % 10 == 0
    }

    companion object {
        private val URL = Regex("https?://[^\\s<>\"')]+")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,7}(?:\\s?[A-Z0-9]{1,4})?\\b")
        private val CARD = Regex("\\b(?:\\d[ -]?){12,18}\\d\\b")
        private val PHONE = Regex("(?<![\\w])\\+?\\(?\\d[\\d\\s().-]{7,}\\d(?![\\w])")
        private val CODE = Regex("(?i)((?:code|otp|passcode|pin|verification)[^\\n\\d]{0,24})(\\b\\d{4,8}\\b)")
    }
}

/** What the person allows, and where. Persisted by the app as JSON. */
@Serializable
data class AiSettings(
    val onDeviceEnabled: Boolean = true,
    /** Try on-device engines first; cloud only when none can do the task. */
    val preferOnDevice: Boolean = true,
    /** The person's chosen BYOK engine, or null for on-device only. */
    val cloudEngineId: String? = null,
    /** Tasks the cloud engine may perform. Summaries and sorting default to on-device only. */
    val cloudTasks: Set<AiTask> = AiTask.entries.filter { it.cloudAllowedByDefault }.toSet(),
    val redactBeforeCloud: Boolean = true,
    /** Categories that never leave the phone, whatever else is allowed. */
    val neverCloudCategories: Set<Category> = setOf(Category.FINANCE, Category.SECURITY, Category.OTP),
    val neverCloudAccounts: Set<Long> = emptySet(),
    /** Upgrade the heuristic tl;dr with a model when one is available. */
    val modelSummaries: Boolean = true,
    /** Cloud tokens per day before cloud tasks pause until tomorrow. 0 = no limit. */
    val dailyCloudTokens: Long = 300_000,
)

/** Facts about a piece of mail that decide whether it may leave the device. */
data class Sensitivity(val category: Category? = null, val accountId: Long? = null) {
    companion object {
        val NONE = Sensitivity()
    }
}

/** Cloud token use per day, so a BYOK key cannot be drained by a busy inbox. */
class UsageLedger(
    private val today: () -> String = { java.time.LocalDate.now().toString() },
    initial: Map<String, Long> = emptyMap(),
    private val persist: (Map<String, Long>) -> Unit = {},
) {
    private val counts = HashMap(initial)

    @Synchronized fun used(): Long = counts[today()] ?: 0

    @Synchronized fun record(tokens: Long) {
        val d = today()
        counts.keys.filter { it != d }.forEach { counts.remove(it) }
        counts[d] = (counts[d] ?: 0) + tokens
        persist(HashMap(counts))
    }
}
