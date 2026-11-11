package dev.aarso.mail.sync

import dev.aarso.mail.ScriptedServer
import dev.aarso.mail.mime.MimeEntity
import dev.aarso.mail.mime.MimeParser
import dev.aarso.mail.model.EmailAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * A tiny in-memory IMAP4rev1 server for sync tests: real wire syntax, real MIME, synthesised
 * ENVELOPE and BODYSTRUCTURE. Supports what the sync engine uses on a plain (non-CONDSTORE) server:
 * LOGIN, LIST, SELECT/EXAMINE, UID SEARCH, UID FETCH, UID STORE, UID MOVE, APPEND, NOOP, LOGOUT.
 */
class FakeImapServer {
    class Msg(val uid: Long, var flags: MutableSet<String>, val raw: ByteArray, val internalDate: String)
    class Box(val name: String, val attrs: String, val uidValidity: Long = 7) {
        val nextUid = AtomicLong(1)
        val messages = java.util.concurrent.CopyOnWriteArrayList<Msg>()
    }

    val boxes = linkedMapOf(
        "INBOX" to Box("INBOX", ""),
        "Sent" to Box("Sent", "\\Sent"),
        "Archive" to Box("Archive", "\\Archive"),
        "Trash" to Box("Trash", "\\Trash"),
    )

    fun deliver(box: String, raw: String, flags: Set<String> = emptySet()): Long {
        val b = boxes.getValue(box)
        val uid = b.nextUid.getAndIncrement()
        b.messages += Msg(uid, flags.toMutableSet(), raw.trimIndent().replace("\n", "\r\n").toByteArray(), "03-Nov-2026 10:00:00 +0000")
        return uid
    }

    private var selected: Box? = null

    fun start(): ScriptedServer = ScriptedServer(greeting = "* OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] fake ready", handler = { line -> handle(this, line) })

    private fun handle(s: ScriptedServer.Session, line: String) {
        val tag = line.substringBefore(' ')
        val cmd = line.substringAfter(' ')
        val upper = cmd.uppercase()
        when {
            upper.startsWith("CAPABILITY") -> { s.send("* CAPABILITY IMAP4rev1 UIDPLUS MOVE"); s.send("$tag OK") }
            upper.startsWith("LOGIN") -> s.send("$tag OK logged in")
            upper.startsWith("LIST") -> {
                boxes.values.forEach { b -> s.send("* LIST (${b.attrs}) \"/\" \"${b.name}\"") }
                s.send("$tag OK list")
            }
            upper.startsWith("SELECT") || upper.startsWith("EXAMINE") -> {
                val name = cmd.substringAfter(' ').trim().trim('"').substringBefore(' ').trim('"')
                val b = boxes[name] ?: return s.send("$tag NO no such mailbox")
                selected = b
                s.send("* ${b.messages.size} EXISTS")
                s.send("* OK [UIDVALIDITY ${b.uidValidity}] v")
                s.send("* OK [UIDNEXT ${b.nextUid.get()}] n")
                s.send("$tag OK [READ-WRITE] selected")
            }
            upper.startsWith("UID SEARCH") -> {
                val b = selected!!
                val crit = cmd.substringAfter("SEARCH ").trim()
                val hits: List<Long> = when {
                    crit.uppercase().startsWith("UID ") -> {
                        val range = crit.substringAfter(' ')
                        val lo = range.substringBefore(':').toLong()
                        val all = b.messages.map { it.uid }
                        val r = all.filter { it >= lo }
                        if (r.isEmpty() && all.isNotEmpty()) listOf(all.max()) else r // "n:*" includes the highest UID
                    }
                    crit.uppercase().startsWith("HEADER MESSAGE-ID") -> {
                        val id = crit.substringAfter("Message-ID ").trim('"')
                        b.messages.filter { String(it.raw).contains(id) }.map { it.uid }
                    }
                    else -> b.messages.map { it.uid }
                }
                s.send("* SEARCH " + hits.joinToString(" "))
                s.send("$tag OK search")
            }
            upper.startsWith("UID FETCH") -> {
                val b = selected!!
                val rest = cmd.substringAfter("FETCH ")
                val set = rest.substringBefore(' ')
                val uids = parseSet(set, b.messages.maxOfOrNull { it.uid } ?: 0)
                for ((i, m) in b.messages.withIndex()) {
                    if (m.uid !in uids) continue
                    s.sendRaw(("* ${i + 1} FETCH (" + fetchItems(m, rest.substringAfter(' ')) + ")\r\n").toByteArray(Charsets.ISO_8859_1))
                }
                s.send("$tag OK fetch")
            }
            upper.startsWith("UID STORE") -> {
                val b = selected!!
                val parts = cmd.split(' ')
                val uids = parseSet(parts[2], Long.MAX_VALUE)
                val add = parts[3].startsWith("+")
                val flags = cmd.substringAfter('(').substringBefore(')').split(' ').filter { it.isNotEmpty() }
                for (m in b.messages) if (m.uid in uids) { if (add) m.flags += flags else m.flags -= flags.toSet() }
                s.send("$tag OK store")
            }
            upper.startsWith("UID MOVE") -> {
                val b = selected!!
                val parts = cmd.split(' ')
                val uids = parseSet(parts[2], Long.MAX_VALUE)
                val dest = boxes.getValue(cmd.substringAfter(parts[2]).trim().trim('"'))
                val moved = b.messages.filter { it.uid in uids }
                val newUids = moved.map { m ->
                    val nu = dest.nextUid.getAndIncrement()
                    dest.messages += Msg(nu, m.flags, m.raw, m.internalDate)
                    nu
                }
                b.messages.removeAll(moved.toSet())
                s.send("* OK [COPYUID ${dest.uidValidity} ${moved.joinToString(",") { it.uid.toString() }} ${newUids.joinToString(",")}] moved")
                s.send("$tag OK move done")
            }
            upper.startsWith("APPEND") -> {
                val size = cmd.substringAfterLast('{').substringBefore('}').trimEnd('+').toInt()
                val name = cmd.substringAfter(' ').trim().substringBefore(' ').trim('"')
                if (!cmd.contains("+}")) s.send("+ ready")
                val bytes = s.readBytes(size)
                s.readLine()
                val b = boxes.getValue(name)
                val uid = b.nextUid.getAndIncrement()
                b.messages += Msg(uid, mutableSetOf("\\Seen"), bytes, "03-Nov-2026 10:00:00 +0000")
                s.send("$tag OK [APPENDUID ${b.uidValidity} $uid] appended")
            }
            upper.startsWith("NOOP") -> s.send("$tag OK")
            upper.startsWith("LOGOUT") -> { s.send("* BYE"); s.send("$tag OK bye"); s.close() }
            else -> s.send("$tag BAD unsupported: $cmd")
        }
    }

    private fun parseSet(set: String, max: Long): Set<Long> = set.split(',').flatMap { p ->
        val a = p.substringBefore(':'); val b = p.substringAfter(':', a)
        val lo = if (a == "*") max else a.toLong()
        val hi = if (b == "*") max else b.toLong()
        (minOf(lo, hi)..maxOf(lo, hi)).toList()
    }.toSet()

    private fun q(s: String?) = if (s == null) "NIL" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun addr(list: List<EmailAddress>) = if (list.isEmpty()) "NIL" else list.joinToString("", "(", ")") { a ->
        "(${q(a.name)} NIL ${q(a.address.substringBefore('@'))} ${q(a.address.substringAfter('@'))})"
    }

    private fun fetchItems(m: Msg, request: String): String {
        val p = MimeParser.parse(m.raw)
        val out = ArrayList<String>()
        out += "UID ${m.uid}"
        val r = request.uppercase()
        if (r.contains("FLAGS")) out += "FLAGS (${m.flags.joinToString(" ")})"
        if (r.contains("INTERNALDATE")) out += "INTERNALDATE \"${m.internalDate}\""
        if (r.contains("RFC822.SIZE")) out += "RFC822.SIZE ${m.raw.size}"
        if (r.contains("ENVELOPE")) {
            out += "ENVELOPE (${q(p.headers["Date"])} ${q(p.headers["Subject"])} ${addr(listOfNotNull(p.from))} ${addr(listOfNotNull(p.from))} " +
                "${addr(listOfNotNull(p.from))} ${addr(p.to)} ${addr(p.cc)} NIL ${q(p.headers["In-Reply-To"])} ${q(p.headers["Message-ID"])})"
        }
        if (r.contains("BODYSTRUCTURE")) out += "BODYSTRUCTURE " + structure(p.root)
        Regex("BODY\\.PEEK\\[([^\\]]*)\\]").findAll(request).forEach { mm ->
            val section = mm.groupValues[1]
            val bytes = when {
                section.isEmpty() -> m.raw
                section.uppercase().startsWith("HEADER") -> {
                    val wanted = section.substringAfter('(').substringBefore(')').split(' ').map { it.lowercase() }.toSet()
                    p.headers.fields.filter { it.first.lowercase() in wanted }.joinToString("") { "${it.first}: ${it.second}\r\n" }.plus("\r\n").toByteArray()
                }
                else -> rawPart(p.root, section)
            }
            out += "BODY[$section] {${bytes.size}}\r\n" + String(bytes, Charsets.ISO_8859_1)
        }
        return out.joinToString(" ")
    }

    /** The part's encoded body as it appears on the wire: re-encode nothing, so use quoted-printable-free test mail. */
    private fun rawPart(root: MimeEntity, section: String): ByteArray =
        root.walk().firstOrNull { it.partId == section && !it.contentType.isMultipart }?.body ?: ByteArray(0)

    private fun structure(e: MimeEntity): String {
        if (e.contentType.isMultipart) {
            return "(" + e.children.joinToString("") { structure(it) } + " ${q(e.contentType.subtype)} (\"boundary\" \"x\") NIL NIL)"
        }
        val params = e.contentType.params.entries.joinToString(" ", "(", ")") { "${q(it.key)} ${q(it.value)}" }.let { if (it == "()") "NIL" else it }
        val disp = e.disposition?.let { d -> "(${q(d.type)} ${d.fileName?.let { "(\"filename\" ${q(it)})" } ?: "NIL"})" } ?: "NIL"
        val lines = if (e.contentType.isText) " ${String(e.body).count { it == '\n' }}" else ""
        return "(${q(e.contentType.type)} ${q(e.contentType.subtype)} $params ${q(e.contentId?.let { "<$it>" })} NIL \"7bit\" ${e.body.size}$lines NIL $disp NIL)"
    }
}
