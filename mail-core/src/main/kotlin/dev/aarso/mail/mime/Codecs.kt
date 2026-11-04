package dev.aarso.mail.mime

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Charset resolution that survives real mail.
 *
 * Mail in the wild names charsets the JDK has never heard of ("utf8", "ks_c_5601-1987", "x-unknown",
 * "cp-850", "unicode-1-1-utf-7") and lies about the ones it has ("iso-8859-1" for text that is
 * really windows-1252). Decoding must never throw and never drop the message: an unknown name
 * resolves through [ALIASES], then a normalised lookup, then UTF-8; malformed bytes are replaced,
 * not fatal.
 */
object MailCharsets {

    private val ALIASES = mapOf(
        "utf8" to "UTF-8",
        "utf-8" to "UTF-8",
        "unicode-1-1-utf-8" to "UTF-8",
        "x-unknown" to "UTF-8",
        "unknown-8bit" to "windows-1252",
        "us-ascii" to "windows-1252",
        "ascii" to "windows-1252",
        "ansi_x3.4-1968" to "windows-1252",
        // ISO-8859-1 mail is overwhelmingly windows-1252 in practice (smart quotes in 0x80–0x9F).
        "iso-8859-1" to "windows-1252",
        "iso8859-1" to "windows-1252",
        "latin1" to "windows-1252",
        "latin-1" to "windows-1252",
        "ks_c_5601-1987" to "x-windows-949",
        "ks_c_5601" to "x-windows-949",
        "euc-kr" to "x-windows-949",
        "gb2312" to "GBK",
        "gb_2312-80" to "GBK",
        "x-gbk" to "GBK",
        "cp936" to "GBK",
        "big5-hkscs" to "Big5-HKSCS",
        "shift-jis" to "Shift_JIS",
        "sjis" to "Shift_JIS",
        "x-sjis" to "Shift_JIS",
        "cp932" to "windows-31j",
        "windows-874" to "x-windows-874",
        "tis-620" to "TIS-620",
        "cp-850" to "IBM850",
        "cp850" to "IBM850",
        "cp1252" to "windows-1252",
        "cp1251" to "windows-1251",
        "cp1250" to "windows-1250",
    )

    fun forName(name: String?): Charset {
        if (name.isNullOrBlank()) return Charsets.UTF_8
        val cleaned = name.trim().trim('"', '\'').lowercase()
        val mapped = ALIASES[cleaned] ?: cleaned
        return runCatching { Charset.forName(mapped) }.getOrNull()
            ?: runCatching { Charset.forName(mapped.replace('_', '-')) }.getOrNull()
            ?: runCatching { Charset.forName(mapped.removePrefix("x-")) }.getOrNull()
            ?: Charsets.UTF_8
    }

    /** Decode without ever throwing; malformed or unmappable input becomes U+FFFD. */
    fun decode(bytes: ByteArray, charset: String?): String = decode(bytes, forName(charset))

    fun decode(bytes: ByteArray, charset: Charset): String {
        if (bytes.isEmpty()) return ""
        val decoder: CharsetDecoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    /**
     * Best guess for 8-bit bytes with no declared charset: strict UTF-8 if it decodes cleanly, else
     * windows-1252, which maps every byte and is what undeclared Western mail almost always is.
     */
    fun sniff(bytes: ByteArray): Charset {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return if (runCatching { strict.decode(ByteBuffer.wrap(bytes)) }.isSuccess) Charsets.UTF_8
        else forName("windows-1252")
    }
}

/** Content-Transfer-Encoding codecs (RFC 2045 §6). Tolerant on decode, strict on encode. */
object TransferEncoding {

    fun decode(bytes: ByteArray, encoding: String?): ByteArray = when (encoding?.trim()?.lowercase()) {
        "base64" -> decodeBase64(bytes)
        "quoted-printable" -> decodeQuotedPrintable(bytes)
        // x-uuencode exists in the wild; it is rare enough that the raw bytes are the honest answer.
        else -> bytes
    }

    private val B64 = IntArray(256) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
    }

    /**
     * A bit-accumulator decoder rather than `java.util.Base64`, because real bodies are not clean:
     * line breaks and stray junk are skipped, missing padding is fine, and padding *mid-stream* (some
     * encoders pad every line) resets the group instead of corrupting everything after it.
     */
    fun decodeBase64(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size * 3 / 4 + 3)
        var acc = 0
        var bits = 0
        for (b in bytes) {
            val v = B64[b.toInt() and 0xFF]
            if (v >= 0) {
                acc = ((acc shl 6) or v) and 0xFFFFFF
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((acc shr bits) and 0xFF)
                }
            } else if (b == '='.code.toByte()) {
                // Padding ends a quantum; the leftover bits of a padded group are never data.
                acc = 0
                bits = 0
            }
        }
        return out.toByteArray()
    }

    fun decodeQuotedPrintable(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            if (b != '='.code) {
                out.write(b); i++; continue
            }
            // Soft line break: "=\r\n" or "=\n", tolerating whitespace an MTA added before the break.
            var j = i + 1
            while (j < n && (bytes[j].toInt() == ' '.code || bytes[j].toInt() == '\t'.code)) j++
            if (j < n && bytes[j].toInt() == '\r'.code) {
                j++
                if (j < n && bytes[j].toInt() == '\n'.code) j++
                i = j; continue
            }
            if (j < n && bytes[j].toInt() == '\n'.code) { i = j + 1; continue }
            if (j == n) { i = n; continue } // "=" at the very end: a soft break with no newline
            val h = hex(bytes.getOrNull(i + 1))
            val l = hex(bytes.getOrNull(i + 2))
            if (h >= 0 && l >= 0) {
                out.write(h * 16 + l); i += 3
            } else {
                out.write(b); i++ // a literal '=' that encodes nothing: keep it, never drop text
            }
        }
        return out.toByteArray()
    }

    private fun hex(b: Byte?): Int {
        if (b == null) return -1
        val c = b.toInt().toChar()
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> c - 'A' + 10
            in 'a'..'f' -> c - 'a' + 10
            else -> -1
        }
    }

    /** RFC 2045 base64 body: 76-character lines, CRLF. */
    fun encodeBase64Lines(bytes: ByteArray): String {
        val enc = Base64.getEncoder().encodeToString(bytes)
        val sb = StringBuilder(enc.length + enc.length / 76 * 2 + 2)
        var i = 0
        while (i < enc.length) {
            sb.append(enc, i, minOf(i + 76, enc.length)).append("\r\n")
            i += 76
        }
        return sb.toString()
    }

    /**
     * Quoted-printable for text bodies: UTF-8 bytes, `=XX` for anything outside printable ASCII,
     * soft breaks to keep lines <= 76, trailing whitespace protected, CRLF line endings.
     */
    fun encodeQuotedPrintable(text: String): String {
        val sb = StringBuilder(text.length + 16)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for ((li, line) in lines.withIndex()) {
            var col = 0
            val bytes = line.toByteArray(Charsets.UTF_8)
            for ((bi, byte) in bytes.withIndex()) {
                val b = byte.toInt() and 0xFF
                val isLast = bi == bytes.lastIndex
                val token = when {
                    b == '='.code -> "=3D"
                    (b == ' '.code || b == '\t'.code) && isLast -> "=%02X".format(b)
                    b in 33..126 || b == ' '.code || b == '\t'.code -> b.toChar().toString()
                    else -> "=%02X".format(b)
                }
                if (col + token.length > 75) { sb.append("=\r\n"); col = 0 }
                // A line must not start with "." in some SMTP paths; dot-stuffing happens in SMTP.
                sb.append(token); col += token.length
            }
            if (li != lines.lastIndex) sb.append("\r\n")
        }
        return sb.toString()
    }
}

/**
 * RFC 2047 encoded-words: `=?utf-8?B?…?=` and `=?iso-8859-1?Q?…?=` in headers.
 *
 * Decoding follows the rules that matter in practice: whitespace *between* two adjacent encoded
 * words is dropped (it is folding, not content); an encoded word split mid-character across two
 * words of the same charset is joined before decoding; and an RFC 2231 language tag
 * (`=?utf-8*en?Q?…?=`) is accepted and ignored.
 */
object EncodedWords {

    private val WORD = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=")

    fun decode(raw: String): String {
        if (!raw.contains("=?")) return raw
        val sb = StringBuilder(raw.length)
        var last = 0
        var pendingCharset: String? = null
        val pendingBytes = ByteArrayOutputStream()

        fun flush() {
            if (pendingCharset != null) {
                sb.append(MailCharsets.decode(pendingBytes.toByteArray(), pendingCharset))
                pendingBytes.reset()
                pendingCharset = null
            }
        }

        for (m in WORD.findAll(raw)) {
            val between = raw.substring(last, m.range.first)
            val charset = m.groupValues[1].substringBefore('*')
            val bytes = when (m.groupValues[2].uppercase()) {
                "B" -> TransferEncoding.decodeBase64(m.groupValues[3].toByteArray(Charsets.US_ASCII))
                else -> decodeQ(m.groupValues[3])
            }
            val adjacent = pendingCharset != null && between.isBlank()
            if (adjacent && pendingCharset.equals(charset, true)) {
                pendingBytes.write(bytes)
            } else {
                flush()
                if (!adjacent) sb.append(between)
                pendingCharset = charset
                pendingBytes.write(bytes)
            }
            last = m.range.last + 1
        }
        flush()
        sb.append(raw, last, raw.length)
        return sb.toString()
    }

    private fun decodeQ(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '_' -> { out.write(' '.code); i++ }
                c == '=' && i + 2 < text.length -> {
                    val v = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.write(v); i += 3 } else { out.write('='.code); i++ }
                }
                else -> { out.write(c.code and 0xFF); i++ }
            }
        }
        return out.toByteArray()
    }

    /** True when [text] can go into a header verbatim. */
    fun isPlainAscii(text: String): Boolean = text.all { it.code in 32..126 }

    /**
     * Encode a header value for the wire. Plain ASCII passes through; anything else becomes UTF-8
     * Base64 encoded-words of at most 75 characters each, split on character boundaries.
     */
    fun encode(text: String): String {
        if (isPlainAscii(text)) return text
        val words = mutableListOf<String>()
        val prefix = "=?UTF-8?B?"
        val maxPayloadBytes = 45 // 45 bytes → 60 base64 chars; with prefix/suffix stays under 75
        val current = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chunk = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (current.size() + chunk.size > maxPayloadBytes) {
                words += prefix + Base64.getEncoder().encodeToString(current.toByteArray()) + "?="
                current.reset()
            }
            current.write(chunk)
            i += Character.charCount(cp)
        }
        if (current.size() > 0) words += prefix + Base64.getEncoder().encodeToString(current.toByteArray()) + "?="
        return words.joinToString("\r\n ")
    }

    /** Encode a display name: quoted if it needs to be, encoded if it is not ASCII. */
    fun encodePhrase(name: String): String {
        if (!isPlainAscii(name)) return encode(name)
        val needsQuote = name.any { it in "()<>[]:;@\\,.\"" }
        return if (needsQuote) "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else name
    }
}
