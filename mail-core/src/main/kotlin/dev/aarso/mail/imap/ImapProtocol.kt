package dev.aarso.mail.imap

import dev.aarso.mail.net.MailException
import dev.aarso.mail.net.ProtocolLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** One IMAP data item (RFC 3501 §4): an atom, a string (quoted or literal), NIL, or a parenthesised list. */
sealed interface ImapValue {
    data class Atom(val text: String) : ImapValue {
        override fun toString() = text
    }

    class Str(val bytes: ByteArray) : ImapValue {
        val text: String by lazy { String(bytes, Charsets.UTF_8) }
        override fun toString() = "\"$text\""
    }

    data object Nil : ImapValue

    data class ListV(val items: List<ImapValue>) : ImapValue {
        override fun toString() = items.joinToString(" ", "(", ")")
    }
}

/** Atoms and strings both read as text; NIL reads as null. */
val ImapValue.text: String?
    get() = when (this) {
        is ImapValue.Atom -> text
        is ImapValue.Str -> text
        ImapValue.Nil -> null
        is ImapValue.ListV -> null
    }

val ImapValue.long: Long? get() = text?.toLongOrNull()
val ImapValue.items: List<ImapValue> get() = (this as? ImapValue.ListV)?.items ?: emptyList()
val ImapValue.bytes: ByteArray?
    get() = when (this) {
        is ImapValue.Str -> bytes
        is ImapValue.Atom -> text.toByteArray(Charsets.UTF_8)
        else -> null
    }

/** `[UIDVALIDITY 3857529045]`, `[PERMANENTFLAGS (\Seen \*)]`, `[APPENDUID 38505 3955]` … */
data class ResponseCode(val name: String, val values: List<ImapValue>) {
    fun arg(i: Int): String? = values.getOrNull(i)?.text
}

sealed interface ImapResponse {
    /** `+ text` — the server wants the rest of a literal, or a SASL step, or is idling. */
    data class Continuation(val text: String) : ImapResponse

    /** `tag OK [CODE] text` or `* OK|NO|BAD|BYE|PREAUTH [CODE] text`. [tag] is null when untagged. */
    data class Status(val tag: String?, val kind: String, val code: ResponseCode?, val text: String) : ImapResponse {
        val isOk: Boolean get() = kind == "OK" || kind == "PREAUTH"
    }

    /** `* 23 EXISTS`, `* 4 FETCH (…)`, `* CAPABILITY …`, `* LIST (…) "/" name`, `* SEARCH 1 2 3` … */
    data class Data(val number: Long?, val keyword: String, val values: List<ImapValue>) : ImapResponse
}

/**
 * Reads IMAP responses from a byte stream.
 *
 * The grammar has one property that rules out a line-based reader: a literal (`{1234}`) is a byte
 * count followed by that many raw octets *in the middle of a response*, and those octets can contain
 * anything including CRLF. So this reads byte-by-byte from a buffered stream, consuming literals by
 * count, and a single [readResponse] call may span many lines.
 *
 * Section keys are atoms with spaces in them — `BODY[HEADER.FIELDS (FROM TO)]<0>` — so an atom that
 * opens a `[` is read through its matching `]`, spaces and parentheses included.
 */
class ImapReader(private val input: InputStream, private val log: ProtocolLog = ProtocolLog.NONE) {

    private var peeked: Int = NONE
    private val lineLog = StringBuilder()

    private fun read(): Int {
        val c = if (peeked != NONE) peeked.also { peeked = NONE } else input.read()
        if (c < 0) throw MailException("Connection closed by server")
        if (c != '\r'.code && c != '\n'.code && lineLog.length < 2000) lineLog.append(c.toChar())
        return c
    }

    private fun peek(): Int {
        if (peeked == NONE) {
            peeked = input.read()
            if (peeked < 0) throw MailException("Connection closed by server")
        }
        return peeked
    }

    fun readResponse(): ImapResponse {
        lineLog.setLength(0)
        val response = parseResponse()
        log.line('<', lineLog.toString())
        return response
    }

    private fun parseResponse(): ImapResponse {
        if (peek() == '+'.code) {
            read()
            if (peek() == ' '.code) read()
            return ImapResponse.Continuation(readRestOfLine())
        }
        val tag = readToken()
        skipSpaces()
        if (tag == "*") {
            val word = readToken()
            val number = word.toLongOrNull()
            if (number != null) {
                skipSpaces()
                val keyword = readToken().uppercase()
                return ImapResponse.Data(number, keyword, readValuesToEndOfLine())
            }
            val upper = word.uppercase()
            return when (upper) {
                "OK", "NO", "BAD", "BYE", "PREAUTH" -> readStatus(null, upper)
                else -> ImapResponse.Data(null, upper, readValuesToEndOfLine())
            }
        }
        val kind = readToken().uppercase()
        return readStatus(tag, kind)
    }

    private fun readStatus(tag: String?, kind: String): ImapResponse.Status {
        skipSpaces()
        var code: ResponseCode? = null
        if (!atEol() && peek() == '['.code) {
            read()
            val sb = StringBuilder()
            var depth = 1
            while (true) {
                val c = read()
                if (c == '['.code) depth++
                if (c == ']'.code && --depth == 0) break
                if (c == '\n'.code) break
                sb.append(c.toChar())
            }
            code = parseCode(sb.toString())
            skipSpaces()
        }
        return ImapResponse.Status(tag, kind, code, readRestOfLine())
    }

    private fun parseCode(content: String): ResponseCode {
        val name = content.substringBefore(' ').uppercase()
        val rest = content.substringAfter(' ', "")
        val values = if (rest.isEmpty()) emptyList() else {
            ImapReader(ByteArrayInputStream((rest + "\r\n").toByteArray(Charsets.UTF_8))).readValuesToEndOfLine()
        }
        return ResponseCode(name, values)
    }

    /** Values up to and including the end of the line, reading through any literals. */
    fun readValuesToEndOfLine(): List<ImapValue> {
        val out = ArrayList<ImapValue>()
        while (true) {
            skipSpaces()
            if (atEol()) { consumeEol(); return out }
            out += readValue()
        }
    }

    private fun readValue(): ImapValue {
        return when (peek()) {
            '('.code -> {
                read()
                val items = ArrayList<ImapValue>()
                while (true) {
                    skipSpaces()
                    val c = peek()
                    if (c == ')'.code) { read(); break }
                    if (c == '\r'.code || c == '\n'.code) {
                        // Unterminated list at end of line: tolerate, stop.
                        break
                    }
                    items += readValue()
                }
                ImapValue.ListV(items)
            }
            '"'.code -> ImapValue.Str(readQuoted())
            '{'.code -> ImapValue.Str(readLiteral())
            '~'.code -> {
                read()
                if (peek() == '{'.code) ImapValue.Str(readLiteral()) else ImapValue.Atom("~" + readAtomTail())
            }
            else -> {
                val atom = readAtomTail()
                if (atom.equals("NIL", ignoreCase = true)) ImapValue.Nil else ImapValue.Atom(atom)
            }
        }
    }

    private fun readQuoted(): ByteArray {
        read() // opening quote
        val out = ByteArrayOutputStream()
        while (true) {
            val c = read()
            when (c) {
                '"'.code -> return out.toByteArray()
                '\\'.code -> out.write(read())
                '\r'.code, '\n'.code -> return out.toByteArray() // broken server; never hang
                else -> out.write(c)
            }
        }
    }

    private fun readLiteral(): ByteArray {
        read() // {
        val digits = StringBuilder()
        while (true) {
            val c = read()
            if (c == '}'.code) break
            if (c == '+'.code || c == '-'.code) continue
            digits.append(c.toChar())
        }
        val n = digits.toString().toIntOrNull() ?: throw MailException("Bad literal length: $digits")
        // CRLF after the size.
        if (peek() == '\r'.code) read()
        if (peek() == '\n'.code) read()
        val buf = ByteArray(n)
        var off = 0
        if (peeked != NONE && n > 0) {
            buf[0] = peeked.toByte(); peeked = NONE; off = 1
        }
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw MailException("Connection closed inside a literal")
            off += r
        }
        lineLog.append("{").append(n).append(" bytes}")
        return buf
    }

    /** An atom, or a `BODY[...]`-shaped key whose brackets may hold spaces and parentheses. */
    private fun readAtomTail(): String {
        val sb = StringBuilder()
        var depth = 0
        while (true) {
            val c = peek()
            if (depth == 0 && (c == ' '.code || c == '('.code || c == ')'.code || c == '\r'.code || c == '\n'.code)) break
            if (depth > 0 && (c == '\r'.code || c == '\n'.code)) break
            if (c == '['.code) depth++
            if (c == ']'.code && depth > 0) depth--
            sb.append(read().toChar())
        }
        return sb.toString()
    }

    private fun readToken(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\r'.code || c == '\n'.code) break
            if (c == '['.code && sb.isNotEmpty()) break
            sb.append(read().toChar())
        }
        return sb.toString()
    }

    private fun readRestOfLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = read()
            if (c == '\n'.code) break
            if (c != '\r'.code) out.write(c)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun skipSpaces() {
        while (peek() == ' '.code) read()
    }

    private fun atEol(): Boolean = peek().let { it == '\r'.code || it == '\n'.code }

    private fun consumeEol() {
        if (peek() == '\r'.code) read()
        if (peek() == '\n'.code) read()
    }

    private companion object {
        const val NONE = -2
    }
}

/**
 * Modified UTF-7 mailbox names (RFC 3501 §5.1.3): `&` shifts into a base64 run of UTF-16 using `,`
 * for `/`; `&-` is a literal ampersand. Used unless the server has enabled UTF8=ACCEPT.
 */
object ModifiedUtf7 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,"

    fun encode(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.code in 0x20..0x7e) {
                sb.append(if (c == '&') "&-" else c.toString())
                i++
                continue
            }
            val start = i
            while (i < s.length && s[i].code !in 0x20..0x7e) i++
            val chunk = s.substring(start, i)
            val bytes = chunk.toByteArray(Charsets.UTF_16BE)
            sb.append('&')
            var bits = 0
            var acc = 0
            for (b in bytes) {
                acc = (acc shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 6) {
                    bits -= 6
                    sb.append(ALPHABET[(acc shr bits) and 0x3F])
                }
            }
            if (bits > 0) sb.append(ALPHABET[(acc shl (6 - bits)) and 0x3F])
            sb.append('-')
        }
        return sb.toString()
    }

    fun decode(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val end = s.indexOf('-', i + 1).takeIf { it >= 0 } ?: s.length
            if (end == i + 1) { sb.append('&'); i = end + 1; continue }
            val run = s.substring(i + 1, end)
            val out = ByteArrayOutputStream()
            var bits = 0
            var acc = 0
            for (ch in run) {
                val v = ALPHABET.indexOf(ch)
                if (v < 0) continue
                acc = (acc shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((acc shr bits) and 0xFF)
                }
            }
            sb.append(String(out.toByteArray(), Charsets.UTF_16BE))
            i = end + 1
        }
        return sb.toString()
    }
}

/** Compact UID sets: `[1,2,3,7,9,10] → "1:3,7,9:10"`, split so no single command line runs away. */
object SequenceSet {
    fun of(uids: Collection<Long>): String {
        if (uids.isEmpty()) return ""
        val sorted = uids.toSortedSet().toList()
        val sb = StringBuilder()
        var start = sorted[0]
        var prev = start
        fun emit() {
            if (sb.isNotEmpty()) sb.append(',')
            if (start == prev) sb.append(start) else sb.append(start).append(':').append(prev)
        }
        for (i in 1 until sorted.size) {
            val u = sorted[i]
            if (u == prev + 1) { prev = u; continue }
            emit(); start = u; prev = u
        }
        emit()
        return sb.toString()
    }

    /** Chunks of at most [maxPerChunk] UIDs whose rendered sets stay short enough for any server. */
    fun chunked(uids: Collection<Long>, maxPerChunk: Int = 500): List<String> =
        uids.toSortedSet().chunked(maxPerChunk).map { of(it) }

    /** Parse `1:3,7,9:*` against [maxUid] for `*`. */
    fun parse(set: String, maxUid: Long = Long.MAX_VALUE): List<Long> = set.split(',').flatMap { part ->
        val a = part.substringBefore(':')
        val b = part.substringAfter(':', a)
        val lo = if (a == "*") maxUid else a.toLongOrNull() ?: return@flatMap emptyList()
        val hi = if (b == "*") maxUid else b.toLongOrNull() ?: return@flatMap emptyList()
        val (x, y) = if (lo <= hi) lo to hi else hi to lo
        if (y - x > 1_000_000) emptyList() else (x..y).toList()
    }
}

/** Render an IMAP astring/quoted string, or signal that it needs a literal. */
internal object ImapQuote {
    fun needsLiteral(s: String, utf8: Boolean): Boolean =
        s.any { it == '\r' || it == '\n' } || (!utf8 && s.any { it.code > 0x7e })

    fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun isAtomSafe(s: String): Boolean = s.isNotEmpty() && s.all {
        it.code in 0x21..0x7e && it !in "(){ %*\"\\]"
    }
}
