package dev.aarso.mail.mime

import dev.aarso.mail.model.EmailAddress
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * An ordered, case-insensitive multimap of header fields, as they appeared.
 *
 * Values are stored *unfolded but undecoded* — RFC 2047 decoding is a per-field decision (an
 * address header must be tokenised before its phrases are decoded, or an encoded comma becomes a
 * separator), so [get] returns raw text and [text] returns decoded text.
 */
class Headers(val fields: List<Pair<String, String>>) {

    operator fun get(name: String): String? = fields.firstOrNull { it.first.equals(name, true) }?.second

    fun all(name: String): List<String> = fields.filter { it.first.equals(name, true) }.map { it.second }

    fun has(name: String): Boolean = fields.any { it.first.equals(name, true) }

    /** The decoded, whitespace-normalised value — for unstructured fields like Subject. */
    fun text(name: String): String? = get(name)?.let { EncodedWords.decode(it).replace(Regex("[\\r\\n\\t]+"), " ").trim() }

    fun addresses(name: String): List<EmailAddress> = all(name).flatMap { AddressParser.parseList(it) }

    fun date(name: String = "Date"): Long? = get(name)?.let { MailDates.parse(it) }

    /** Message-ID-shaped tokens, e.g. References: `<a@x> <b@y>`. Brackets stripped. */
    fun messageIds(name: String): List<String> = all(name).flatMap { MessageIds.parse(it) }

    fun contentType(): ContentType = ContentType.parse(get("Content-Type"))

    fun disposition(): ContentDisposition? = get("Content-Disposition")?.let { ContentDisposition.parse(it) }

    companion object {
        val EMPTY = Headers(emptyList())

        /**
         * Parse a header block (everything up to the first empty line). Tolerates bare LF, a missing
         * final newline, leading garbage lines without a colon (dropped), and continuation lines.
         */
        fun parse(block: String): Headers {
            val out = ArrayList<Pair<String, String>>()
            var name: String? = null
            val value = StringBuilder()
            fun commit() {
                name?.let { out += it to value.toString().trim() }
                name = null
                value.setLength(0)
            }
            for (rawLine in block.split('\n')) {
                val line = rawLine.removeSuffix("\r")
                if (line.isEmpty()) continue
                if ((line[0] == ' ' || line[0] == '\t') && name != null) {
                    // Unfold: the CRLF goes, the leading whitespace stays (RFC 5322 §2.2.3).
                    value.append(line)
                    continue
                }
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                commit()
                name = line.substring(0, colon).trim()
                value.append(line.substring(colon + 1))
            }
            commit()
            return Headers(out)
        }
    }
}

/** A parsed Content-Type: `text/plain; charset="utf-8"; format=flowed`. */
data class ContentType(val type: String, val subtype: String, val params: Map<String, String>) {
    val mimeType: String get() = "$type/$subtype"
    val charset: String? get() = params["charset"]
    val boundary: String? get() = params["boundary"]
    val name: String? get() = params["name"]
    val isMultipart: Boolean get() = type == "multipart"
    val isText: Boolean get() = type == "text"
    fun isType(mime: String): Boolean = mimeType.equals(mime, true)

    companion object {
        val DEFAULT = ContentType("text", "plain", mapOf("charset" to "us-ascii"))

        fun parse(raw: String?): ContentType {
            if (raw.isNullOrBlank()) return DEFAULT
            val (value, params) = HeaderParams.split(raw)
            val slash = value.indexOf('/')
            if (slash <= 0) {
                // "text" alone, or garbage: RFC 2045 says treat unknown as text/plain.
                return if (value.equals("text", true)) DEFAULT.copy(params = params) else DEFAULT
            }
            val type = value.substring(0, slash).trim().lowercase()
            val subtype = value.substring(slash + 1).trim().lowercase().ifEmpty { "octet-stream" }
            return ContentType(type, subtype, params)
        }
    }
}

data class ContentDisposition(val type: String, val params: Map<String, String>) {
    val fileName: String? get() = params["filename"]
    val isAttachment: Boolean get() = type == "attachment"
    val isInline: Boolean get() = type == "inline"

    companion object {
        fun parse(raw: String): ContentDisposition {
            val (value, params) = HeaderParams.split(raw)
            return ContentDisposition(value.trim().lowercase(), params)
        }
    }
}

/**
 * `value; key=val; key2="quoted; val"` with RFC 2231 continuations and charset encoding
 * (`filename*0*=utf-8''%E2%9C%93; filename*1=.pdf`) and RFC 2047 words inside quoted values
 * (non-standard but universal). Keys are lower-cased.
 */
object HeaderParams {

    fun split(raw: String): Pair<String, Map<String, String>> {
        val parts = splitSemicolons(raw)
        val value = parts.firstOrNull()?.trim().orEmpty()
        val simple = LinkedHashMap<String, String>()
        // name -> (index -> (encoded?, text))
        val continued = LinkedHashMap<String, java.util.TreeMap<Int, Pair<Boolean, String>>>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val key = p.substring(0, eq).trim().lowercase()
            var v = p.substring(eq + 1).trim()
            if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = unquote(v)
            val star = key.indexOf('*')
            if (star < 0) {
                simple.putIfAbsent(key, EncodedWords.decode(v))
                continue
            }
            val base = key.substring(0, star)
            val rest = key.substring(star + 1)
            when {
                rest.isEmpty() -> continued.getOrPut(base) { java.util.TreeMap() }[0] = true to v // key*=...
                else -> {
                    val encoded = rest.endsWith("*")
                    val idx = rest.removeSuffix("*").toIntOrNull() ?: continue
                    continued.getOrPut(base) { java.util.TreeMap() }[idx] = encoded to v
                }
            }
        }
        for ((name, pieces) in continued) {
            var charset: String? = null
            val bytes = java.io.ByteArrayOutputStream()
            for ((index, piece) in pieces) {
                val (encoded, text) = piece
                var t = text
                if (encoded && index == 0) {
                    // charset'language'value
                    val first = t.indexOf('\'')
                    val second = if (first >= 0) t.indexOf('\'', first + 1) else -1
                    if (first >= 0 && second > first) {
                        charset = t.substring(0, first).ifEmpty { null }
                        t = t.substring(second + 1)
                    }
                }
                if (encoded) bytes.write(percentDecode(t)) else bytes.write(t.toByteArray(Charsets.UTF_8))
            }
            // An RFC 2231 value wins over a plain one of the same name: it is the more precise form.
            simple[name] = MailCharsets.decode(bytes.toByteArray(), charset ?: "UTF-8")
        }
        return value to simple
    }

    private fun splitSemicolons(raw: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var escape = false
        for (c in raw) {
            when {
                escape -> { sb.append(c); escape = false }
                c == '\\' && inQuote -> { sb.append(c); escape = true }
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c == ';' && !inQuote -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    private fun unquote(v: String): String {
        val inner = v.substring(1, v.length - 1)
        val sb = StringBuilder(inner.length)
        var escape = false
        for (c in inner) {
            if (escape) { sb.append(c); escape = false } else if (c == '\\') escape = true else sb.append(c)
        }
        return sb.toString()
    }

    private fun percentDecode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toByteArray()
    }

    /** Render `key="value"` safely for an outgoing header, RFC 2231-encoding non-ASCII values. */
    fun render(key: String, value: String): String =
        if (EncodedWords.isPlainAscii(value)) {
            "$key=\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            val enc = value.toByteArray(Charsets.UTF_8).joinToString("") { b ->
                val i = b.toInt() and 0xFF
                val ch = i.toChar()
                if (ch.isLetterOrDigit() && i < 128 || ch in "-._~") ch.toString() else "%%%02X".format(i)
            }
            "$key*=UTF-8''$enc"
        }
}

/**
 * RFC 5322 address lists, parsed the way mail actually arrives: groups (`undisclosed-recipients:;`),
 * quoted names with escaped quotes, comments, encoded words inside names, bare addresses, and the
 * outright malformed (`John Smith john@x.com`, `<a@b>`, trailing commas) — all degrade to the best
 * reading rather than nothing.
 */
object AddressParser {

    fun parseList(raw: String?): List<EmailAddress> {
        if (raw.isNullOrBlank()) return emptyList()
        return splitTopLevel(raw).mapNotNull { parseOne(it) }
    }

    fun parseOne(raw: String): EmailAddress? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        // Group syntax: "Name: a@b, c@d;" — the caller split on commas; strip a "Group:" prefix.
        val colon = topLevelIndexOf(s, ':')
        if (colon >= 0 && !s.substring(0, colon).contains('@') && !s.substring(0, colon).contains('<')) {
            s = s.substring(colon + 1)
        }
        s = s.trim().removeSuffix(";").trim()
        if (s.isEmpty()) return null

        val lt = topLevelIndexOf(s, '<')
        if (lt >= 0) {
            val gt = s.indexOf('>', lt)
            val addr = (if (gt > lt) s.substring(lt + 1, gt) else s.substring(lt + 1)).trim()
            val phrase = s.substring(0, lt).trim()
            val name = cleanPhrase(phrase).ifBlank { cleanPhrase(stripComments(s.substring(if (gt > lt) gt + 1 else s.length))) }
            if (addr.isBlank()) return null
            return EmailAddress(stripRoute(addr), name.ifBlank { null })
        }

        // No angle brackets: "a@b (Name)" or "Name a@b" or "a@b".
        val comment = Regex("\\(([^)]*)\\)").find(s)?.groupValues?.get(1)
        val bare = stripComments(s).trim()
        val token = bare.split(Regex("\\s+")).lastOrNull { it.contains('@') } ?: bare
        if (!token.contains('@')) {
            return null
        }
        val namePart = bare.replace(token, "").trim()
        val name = cleanPhrase(namePart).ifBlank { comment?.let { cleanPhrase(it) } ?: "" }
        return EmailAddress(token.trim('<', '>', '"', '\'', ',', ';'), name.ifBlank { null })
    }

    private fun stripRoute(addr: String): String = if (addr.startsWith("@") && addr.contains(':')) addr.substringAfter(':') else addr

    private fun cleanPhrase(p: String): String {
        var s = p.trim()
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) {
            s = s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            // Phrases made of several quoted/unquoted words: drop the quotes, keep the words.
            s = s.replace("\\\"", "\u0000").replace("\"", "").replace("\u0000", "\"")
        }
        return EncodedWords.decode(s).replace(Regex("\\s+"), " ").trim()
    }

    private fun stripComments(s: String): String {
        val sb = StringBuilder()
        var depth = 0
        var inQuote = false
        for (c in s) {
            when {
                c == '"' && depth == 0 -> { inQuote = !inQuote; sb.append(c) }
                inQuote -> sb.append(c)
                c == '(' -> depth++
                c == ')' && depth > 0 -> depth--
                depth == 0 -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Split on commas that are not inside quotes, angle brackets or comments. Groups end at ';'. */
    private fun splitTopLevel(raw: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var angle = 0
        var paren = 0
        var escape = false
        for (c in raw) {
            if (escape) { sb.append(c); escape = false; continue }
            when {
                c == '\\' && inQuote -> { sb.append(c); escape = true }
                c == '"' && paren == 0 -> { inQuote = !inQuote; sb.append(c) }
                inQuote -> sb.append(c)
                c == '(' -> { paren++; sb.append(c) }
                c == ')' && paren > 0 -> { paren--; sb.append(c) }
                c == '<' && paren == 0 -> { angle++; sb.append(c) }
                c == '>' && paren == 0 && angle > 0 -> { angle--; sb.append(c) }
                (c == ',' || c == ';') && angle == 0 && paren == 0 -> {
                    if (c == ';') sb.append(c)
                    out += sb.toString(); sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    private fun topLevelIndexOf(s: String, target: Char): Int {
        var inQuote = false
        var paren = 0
        for ((i, c) in s.withIndex()) {
            when {
                c == '"' && paren == 0 -> inQuote = !inQuote
                inQuote -> {}
                c == '(' -> paren++
                c == ')' && paren > 0 -> paren--
                c == target && paren == 0 -> return i
            }
        }
        return -1
    }

    /** Render for an outgoing header: phrase encoded as needed, address verbatim. */
    fun render(list: List<EmailAddress>): String = list.joinToString(", ") { a ->
        if (a.name.isNullOrBlank()) a.address else EncodedWords.encodePhrase(a.name) + " <" + a.address + ">"
    }
}

/** Message-ID and References tokens: `<abc@host>` → `abc@host`. Order preserved, duplicates removed. */
object MessageIds {
    private val TOKEN = Regex("<([^<>\\s]+)>")
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val bracketed = TOKEN.findAll(raw).map { it.groupValues[1] }.toList()
        if (bracketed.isNotEmpty()) return bracketed.distinct()
        // Broken mailers omit the brackets entirely.
        return raw.split(Regex("[\\s,]+")).filter { it.contains('@') }.distinct()
    }
}

/**
 * RFC 5322 dates, including the obsolete and the broken: missing day-of-week, two-digit years,
 * named zones (EST, PDT, UT, GMT, Z), missing seconds, "+0000 (UTC)" comments, single-digit hours,
 * and the occasional ISO-8601 from a misbehaving bot. Returns null rather than guessing wildly.
 */
object MailDates {

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val ZONES = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0,
        "EST" to -5 * 60, "EDT" to -4 * 60, "CST" to -6 * 60, "CDT" to -5 * 60,
        "MST" to -7 * 60, "MDT" to -6 * 60, "PST" to -8 * 60, "PDT" to -7 * 60,
        "CET" to 60, "CEST" to 120, "BST" to 60, "IST" to 330, "JST" to 540, "AEST" to 600, "AEDT" to 660,
    )
    private val PATTERN = Regex(
        "(?:[A-Za-z]{3,9},?\\s*)?(\\d{1,2})[\\s-]+([A-Za-z]{3,9})[\\s-]+(\\d{2,4})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([+-]\\d{4}|[A-Za-z]{1,5})?",
    )

    fun parse(raw: String): Long? {
        val s = raw.trim()
        PATTERN.find(s)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = MONTHS.indexOf(m.groupValues[2].take(3).lowercase()) + 1
            if (month == 0) return null
            var year = m.groupValues[3].toInt()
            if (m.groupValues[3].length == 2) year += if (year < 50) 2000 else 1900
            if (m.groupValues[3].length == 3) year += 1900
            val hour = m.groupValues[4].toInt()
            val minute = m.groupValues[5].toInt()
            val second = m.groupValues[6].toIntOrNull() ?: 0
            val zoneRaw = m.groupValues[7]
            val offsetMinutes = when {
                zoneRaw.isEmpty() -> 0
                zoneRaw[0] == '+' || zoneRaw[0] == '-' -> {
                    val sign = if (zoneRaw[0] == '-') -1 else 1
                    val hh = zoneRaw.substring(1, 3).toInt()
                    val mm = zoneRaw.substring(3, 5).toInt()
                    sign * (hh * 60 + mm)
                }
                else -> ZONES[zoneRaw.uppercase()] ?: 0
            }
            return runCatching {
                val ldt = LocalDateTime.of(year, month, day, hour.coerceIn(0, 23), minute.coerceIn(0, 59), second.coerceIn(0, 59))
                ldt.toInstant(ZoneOffset.ofTotalSeconds(offsetMinutes.coerceIn(-18 * 60, 18 * 60) * 60)).toEpochMilli()
            }.getOrNull()
        }
        return runCatching { ZonedDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
    }

    private val FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)

    fun format(epochMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        FORMAT.format(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** IMAP INTERNALDATE / APPEND date-time: `"17-Jul-1996 02:44:25 -0700"`. */
    fun formatImap(epochMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss Z", Locale.US).format(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** IMAP SEARCH date: `1-Feb-1994`. */
    fun formatImapSearch(epochMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.US).format(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone))
}
