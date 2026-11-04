package dev.aarso.mail.imap

import dev.aarso.mail.mime.EncodedWords
import dev.aarso.mail.mime.MailDates
import dev.aarso.mail.mime.MessageIds
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.PartInfo
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** RFC 3501 ENVELOPE, decoded. */
data class Envelope(
    val date: Long?,
    val subject: String,
    val from: List<EmailAddress>,
    val sender: List<EmailAddress>,
    val replyTo: List<EmailAddress>,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>,
    val inReplyTo: String?,
    val messageId: String?,
) {
    companion object {
        fun parse(v: ImapValue): Envelope? {
            val f = v.items
            if (f.size < 10) return null
            return Envelope(
                date = f[0].text?.let { MailDates.parse(it) },
                subject = f[1].text?.let { EncodedWords.decode(it).replace(Regex("[\\r\\n\\t]+"), " ").trim() }.orEmpty(),
                from = addresses(f[2]),
                sender = addresses(f[3]),
                replyTo = addresses(f[4]),
                to = addresses(f[5]),
                cc = addresses(f[6]),
                bcc = addresses(f[7]),
                inReplyTo = f[8].text?.let { MessageIds.parse(it).firstOrNull() },
                messageId = f[9].text?.let { MessageIds.parse(it).firstOrNull() },
            )
        }

        /** `((name adl mailbox host) …)`; host NIL marks RFC 2822 group start/end, which we skip. */
        private fun addresses(v: ImapValue): List<EmailAddress> = v.items.mapNotNull { a ->
            val p = a.items
            if (p.size < 4) return@mapNotNull null
            val mailbox = p[2].text ?: return@mapNotNull null
            val host = p[3].text ?: return@mapNotNull null
            val name = p[0].text?.let { EncodedWords.decode(it).trim() }?.takeIf { it.isNotEmpty() }
            EmailAddress("$mailbox@$host", name)
        }
    }
}

/** One node of BODYSTRUCTURE with its IMAP section number. */
data class BodyPart(
    val partId: String,
    val type: String,
    val subtype: String,
    val params: Map<String, String>,
    val id: String?,
    val encoding: String?,
    val size: Long,
    val disposition: String?,
    val dispositionParams: Map<String, String>,
    val children: List<BodyPart> = emptyList(),
) {
    val mimeType: String get() = "$type/$subtype"
    val isMultipart: Boolean get() = type == "multipart"
    val fileName: String?
        get() = (dispositionParams["filename"] ?: params["name"])?.let { EncodedWords.decode(it) }?.takeIf { it.isNotBlank() }

    val isAttachment: Boolean
        get() = when {
            isMultipart -> false
            disposition == "attachment" -> true
            mimeType == "message/rfc822" -> true
            type == "text" && fileName == null -> false
            id != null && disposition != "attachment" -> false
            fileName != null -> true
            type != "text" -> true
            else -> false
        }

    fun leaves(): List<BodyPart> = if (isMultipart) children.flatMap { it.leaves() } else listOf(this)

    fun toPartInfo(): PartInfo = PartInfo(
        partId = partId,
        mimeType = mimeType,
        charset = params["charset"],
        fileName = fileName,
        contentId = id?.trim('<', '>'),
        disposition = disposition,
        encoding = encoding,
        size = size,
    )

    /**
     * The section to fetch for the readable body: the preferred text alternative. Prefers HTML when
     * [preferHtml] (the reader) and plain text otherwise (previews, summaries), falling back to the
     * other, and never picks an attachment.
     */
    fun textSection(preferHtml: Boolean): BodyPart? {
        val texts = bodyTexts(this)
        val wanted = if (preferHtml) "html" else "plain"
        return texts.firstOrNull { it.subtype == wanted } ?: texts.firstOrNull()
    }

    private fun bodyTexts(p: BodyPart): List<BodyPart> = when {
        p.isMultipart && p.subtype == "alternative" -> p.children.flatMap { bodyTexts(it) }
        p.isMultipart -> p.children.firstOrNull()?.let { first ->
            // In mixed/related the first child is the body; later children are attachments.
            bodyTexts(first) + p.children.drop(1).filter { it.type == "text" && !it.isAttachment && it.subtype in setOf("plain", "html") }
        }.orEmpty()
        p.type == "text" && !p.isAttachment && p.subtype in setOf("plain", "html") -> listOf(p)
        else -> emptyList()
    }

    companion object {
        /** Parse BODYSTRUCTURE / BODY, numbering parts the way BODY[section] expects them. */
        fun parse(v: ImapValue, partId: String = ""): BodyPart? {
            val f = v.items
            if (f.isEmpty()) return null
            if (f[0] is ImapValue.ListV) {
                val children = ArrayList<BodyPart>()
                var i = 0
                while (i < f.size && f[i] is ImapValue.ListV) {
                    val childId = if (partId.isEmpty()) "${i + 1}" else "$partId.${i + 1}"
                    parse(f[i], childId)?.let(children::add)
                    i++
                }
                val subtype = f.getOrNull(i)?.text?.lowercase() ?: "mixed"
                val params = paramMap(f.getOrNull(i + 1))
                val (disp, dispParams) = disposition(f.getOrNull(i + 2))
                return BodyPart(partId, "multipart", subtype, params, null, null, 0, disp, dispParams, children)
            }
            val type = f.getOrNull(0)?.text?.lowercase() ?: "application"
            val subtype = f.getOrNull(1)?.text?.lowercase() ?: "octet-stream"
            val params = paramMap(f.getOrNull(2))
            val id = f.getOrNull(3)?.text
            val encoding = f.getOrNull(5)?.text?.lowercase()
            val size = f.getOrNull(6)?.long ?: 0
            // Extension data starts after the basic fields: +1 for text (lines), +3 for message/rfc822.
            var ext = 7
            if (type == "text") ext += 1
            if (type == "message" && subtype == "rfc822") ext += 3
            // body-ext-1part: md5, disposition, language, location
            val (disp, dispParams) = disposition(f.getOrNull(ext + 1))
            val myId = partId.ifEmpty { "1" }
            val children = if (type == "message" && subtype == "rfc822") {
                // A multipart inside numbers its children N.1, N.2; a single-part body inside is N.1.
                listOfNotNull(f.getOrNull(8)?.let { inner -> parse(inner, myId)?.let { if (it.isMultipart) it else it.copy(partId = "$myId.1") } })
            } else emptyList()
            return BodyPart(myId, type, subtype, params, id, encoding, size, disp, dispParams, children)
        }

        private fun paramMap(v: ImapValue?): Map<String, String> {
            val items = v?.items ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            var i = 0
            while (i + 1 < items.size) {
                val k = items[i].text?.lowercase()
                val value = items[i + 1].text
                if (k != null && value != null) out[k.removeSuffix("*")] = if (k.endsWith("*")) decode2231(value) else value
                i += 2
            }
            return out
        }

        private fun decode2231(v: String): String {
            val second = v.indexOf('\'', v.indexOf('\'') + 1)
            if (second < 0) return v
            val charset = v.substringBefore('\'')
            val raw = v.substring(second + 1)
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < raw.length) {
                val hex = if (raw[i] == '%' && i + 2 < raw.length) raw.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    bytes.write(hex); i += 3
                } else {
                    bytes.write(raw[i].code); i++
                }
            }
            return dev.aarso.mail.mime.MailCharsets.decode(bytes.toByteArray(), charset)
        }

        private fun disposition(v: ImapValue?): Pair<String?, Map<String, String>> {
            val items = v?.items ?: return null to emptyMap()
            if (items.isEmpty()) return null to emptyMap()
            return items[0].text?.lowercase() to paramMap(items.getOrNull(1))
        }
    }
}

/** One `* n FETCH (…)` response, typed. Unknown items stay reachable through [raw]. */
class FetchItem(val sequence: Long, val raw: Map<String, ImapValue>) {
    val uid: Long? get() = raw["UID"]?.long
    val flags: List<String>? get() = raw["FLAGS"]?.items?.mapNotNull { it.text }
    val modSeq: Long? get() = raw["MODSEQ"]?.items?.firstOrNull()?.long
    val size: Long? get() = raw["RFC822.SIZE"]?.long
    val internalDate: Long? get() = raw["INTERNALDATE"]?.text?.let { parseInternalDate(it) }
    val envelope: Envelope? get() = raw["ENVELOPE"]?.let { Envelope.parse(it) }
    val bodyStructure: BodyPart? get() = (raw["BODYSTRUCTURE"] ?: raw["BODY"])?.let { BodyPart.parse(it) }

    /** Gmail X-GM-EXT-1. */
    val gmailLabels: List<String>? get() = raw["X-GM-LABELS"]?.items?.mapNotNull { it.text?.let(ModifiedUtf7::decode) }
    val gmailThreadId: String? get() = raw["X-GM-THRID"]?.text
    val gmailMessageId: String? get() = raw["X-GM-MSGID"]?.text

    /** RFC 8474 OBJECTID. */
    val emailId: String? get() = raw["EMAILID"]?.items?.firstOrNull()?.text
    val threadId: String? get() = raw["THREADID"]?.items?.firstOrNull()?.text

    /** RFC 8970 PREVIEW. */
    val preview: String? get() = raw["PREVIEW"]?.text

    /**
     * The bytes of a fetched section. [spec] is what was asked for inside the brackets ("", "1.2",
     * "HEADER", "HEADER.FIELDS (FROM)"); a partial fetch's `<origin>` suffix is ignored.
     */
    fun section(spec: String): ByteArray? {
        val want = spec.uppercase()
        for ((k, v) in raw) {
            if (!(k.startsWith("BODY[") || k.startsWith("BINARY["))) continue
            val inner = k.substringAfter('[').substringBeforeLast(']').uppercase()
            val norm = inner.replace(Regex("\\s+"), " ")
            if (norm == want || (want.startsWith("HEADER.FIELDS") && norm.startsWith("HEADER.FIELDS"))) return v.bytes ?: ByteArray(0)
        }
        return null
    }

    companion object {
        private val INTERNAL = DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss Z", Locale.US)

        fun parseInternalDate(s: String): Long? =
            runCatching { ZonedDateTime.parse(s.trim(), INTERNAL).toInstant().toEpochMilli() }.getOrNull()
                ?: MailDates.parse(s)

        /** Build from the values of a FETCH response: `(KEY value KEY value …)`. */
        fun from(sequence: Long, values: List<ImapValue>): FetchItem {
            val list = values.firstOrNull()?.items ?: emptyList()
            val map = LinkedHashMap<String, ImapValue>()
            var i = 0
            while (i + 1 < list.size) {
                val key = list[i].text?.uppercase() ?: break
                map[normaliseKey(key)] = list[i + 1]
                i += 2
            }
            return FetchItem(sequence, map)
        }

        /** `BODY[1]<0>` → `BODY[1]`, `BODY.PEEK[...]` (some servers echo it) → `BODY[...]`. */
        private fun normaliseKey(k: String): String {
            val noPeek = k.replace("BODY.PEEK[", "BODY[").replace("BINARY.PEEK[", "BINARY[")
            val lt = noPeek.lastIndexOf('<')
            return if (lt > noPeek.lastIndexOf(']')) noPeek.substring(0, lt) else noPeek
        }
    }
}

/** What arrived unasked while a mailbox was selected — the raw material of push. */
sealed interface MailboxEvent {
    data class Exists(val count: Long) : MailboxEvent
    data class Expunged(val sequence: Long) : MailboxEvent
    data class Vanished(val uids: List<Long>, val earlier: Boolean) : MailboxEvent
    data class FlagsChanged(val item: FetchItem) : MailboxEvent
    data class Recent(val count: Long) : MailboxEvent
    data class Bye(val text: String) : MailboxEvent
}

/** A `* LIST` (or XLIST) row. */
data class ListedMailbox(
    val name: String,
    val delimiter: Char?,
    val attributes: Set<String>,
    val status: Map<String, Long> = emptyMap(),
) {
    val selectable: Boolean get() = attributes.none { it.equals("\\Noselect", true) || it.equals("\\NonExistent", true) }
    val leaf: String get() = delimiter?.let { name.substringAfterLast(it) } ?: name
}

data class SelectedMailbox(
    val name: String,
    val exists: Long,
    val uidValidity: Long,
    val uidNext: Long,
    val highestModSeq: Long,
    val flags: List<String>,
    val permanentFlags: List<String>,
    val readOnly: Boolean,
    val noModSeq: Boolean,
    val vanished: List<Long>,
    val changed: List<FetchItem>,
)

data class CopyUid(val uidValidity: Long, val sourceUids: List<Long>, val destUids: List<Long>)
data class AppendUid(val uidValidity: Long, val uid: Long)
