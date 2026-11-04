package dev.aarso.mail.mime

/**
 * HTML as mail uses it — flattened to text, stripped of what spies on the reader, and mined for
 * the structured data senders embed for exactly this purpose.
 *
 * Regex-based on purpose: this runs over every incoming message on a phone, often in a background
 * sync, and a DOM parser per message is the wrong cost. Each pattern is linear (no nested
 * quantifiers over the same characters), so a 2 MB marketing email cannot stall a sync.
 */
object HtmlText {

    private val DROP_BLOCKS = Regex("(?is)<(head|style|script|noscript|template|svg|title)\\b[^>]*>.*?</\\1\\s*>")
    private val COMMENTS = Regex("(?s)<!--.*?-->")
    private val BREAKS = Regex("(?i)<br\\s*/?>")
    private val BLOCK_END = Regex("(?i)</(p|div|h[1-6]|tr|table|blockquote|section|article|header|footer|ul|ol|pre)\\s*>")
    private val BLOCK_START = Regex("(?i)<(p|div|h[1-6]|table|blockquote|section|article|ul|ol|pre)\\b[^>]*>")
    private val LIST_ITEM = Regex("(?i)<li\\b[^>]*>")
    private val CELL_END = Regex("(?i)</t[dh]\\s*>")
    private val HR = Regex("(?i)<hr\\b[^>]*>")
    private val IMG_ALT = Regex("(?i)<img\\b[^>]*\\balt\\s*=\\s*\"([^\"]{1,80})\"[^>]*>")
    private val TAGS = Regex("(?s)<[^>]*>")
    private val SPACES = Regex("[ \\t\\u00A0\\u200B\\u200C\\u200D\\uFEFF\\u034F]+")
    private val SPACE_NEWLINE = Regex(" *\\n *")
    private val MANY_NEWLINES = Regex("\\n{3,}")
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")

    /** Readable text from HTML. Paragraph structure survives; layout tables collapse to lines. */
    fun toText(html: String): String {
        var s = html
        s = DROP_BLOCKS.replace(s, " ")
        s = COMMENTS.replace(s, " ")
        s = IMG_ALT.replace(s) { m -> " " + m.groupValues[1] + " " }
        s = BREAKS.replace(s, "\n")
        s = LIST_ITEM.replace(s, "\n• ")
        s = BLOCK_START.replace(s, "\n")
        s = BLOCK_END.replace(s, "\n")
        s = CELL_END.replace(s, " ")
        s = HR.replace(s, "\n———\n")
        s = TAGS.replace(s, "")
        s = decodeEntities(s)
        s = SPACES.replace(s, " ")
        s = SPACE_NEWLINE.replace(s, "\n")
        s = MANY_NEWLINES.replace(s, "\n\n")
        return s.trim()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”",
        "ldquo" to "“", "bull" to "•", "middot" to "·", "copy" to "©", "reg" to "®", "trade" to "™",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "times" to "×", "divide" to "÷",
        "deg" to "°", "plusmn" to "±", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "laquo" to "«",
        "raquo" to "»", "zwnj" to "", "zwj" to "", "shy" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "sect" to "§", "para" to "¶", "iexcl" to "¡", "iquest" to "¿", "rarr" to "→", "larr" to "←",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "aacute" to "á", "agrave" to "à", "acirc" to "â",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "ccedil" to "ç", "ntilde" to "ñ", "oacute" to "ó", "uacute" to "ú", "iacute" to "í", "oslash" to "ø",
        "aring" to "å", "aelig" to "æ", "check" to "✓", "hearts" to "♥", "star" to "☆",
    )

    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
                else -> NAMED[e] ?: NAMED[e.lowercase()] ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int): String =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else "\uFFFD"

    // ------------------------------------------------------------------------------------------
    // Quotes and signatures
    // ------------------------------------------------------------------------------------------

    /** Attribution lines that open a quoted reply, in the languages mail mostly arrives in. */
    private val ATTRIBUTION = listOf(
        Regex("(?im)^\\s*On\\b.{0,200}\\bwrote:\\s*$"),
        Regex("(?im)^\\s*Le\\b.{0,200}\\ba écrit\\s*:\\s*$"),
        Regex("(?im)^\\s*Am\\b.{0,200}\\bschrieb.{0,80}:\\s*$"),
        Regex("(?im)^\\s*El\\b.{0,200}\\bescribió:\\s*$"),
        Regex("(?im)^\\s*Il\\b.{0,200}\\bha scritto:\\s*$"),
        Regex("(?im)^\\s*Em\\b.{0,200}\\bescreveu:\\s*$"),
        Regex("(?im)^\\s*Op\\b.{0,200}\\bschreef.{0,80}:\\s*$"),
        Regex("(?im)^\\s*-{2,}\\s*Original Message\\s*-{2,}\\s*$"),
        Regex("(?im)^\\s*-{2,}\\s*Forwarded message\\s*-{2,}\\s*$"),
        Regex("(?im)^\\s*_{20,}\\s*$"),
        Regex("(?im)^\\s*From:\\s.{1,200}\\n\\s*(Sent|Date):\\s"),
        Regex("(?im)^\\s*\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}.{0,120}<[^>]+@[^>]+>.{0,40}:\\s*$"),
    )

    /**
     * The part of a message its author actually wrote this time: quoted history, attribution lines
     * and the signature removed. Never returns empty for a non-empty input — if stripping would
     * leave nothing (a pure forward), the original text is the honest answer.
     */
    fun newContent(text: String): String {
        if (text.isBlank()) return text
        var cut = text.length
        for (rx in ATTRIBUTION) {
            val m = rx.find(text) ?: continue
            if (m.range.first in 1 until cut) cut = m.range.first
        }
        var s = text.substring(0, cut)
        // Trailing block of "> " quoted lines.
        val lines = s.split('\n').toMutableList()
        while (lines.isNotEmpty() && (lines.last().trimStart().startsWith(">") || lines.last().isBlank())) lines.removeAt(lines.lastIndex)
        s = lines.joinToString("\n")
        // RFC 3676 signature separator.
        val sig = Regex("(?m)^-- ?$").find(s)
        if (sig != null && sig.range.first > 0) s = s.substring(0, sig.range.first)
        // Mobile footers that add nothing.
        s = s.replace(Regex("(?im)^\\s*(Sent from my (iPhone|iPad|Android|Samsung.*|Galaxy.*|Pixel.*)|Get Outlook for (iOS|Android))\\s*$"), "")
        val out = s.trim()
        return out.ifEmpty { text.trim() }
    }

    /** A one-paragraph preview: new content, whitespace collapsed, cut on a word boundary. */
    fun preview(text: String, max: Int = 220): String {
        val flat = newContent(text).replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        val cut = flat.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        return flat.substring(0, cut).trimEnd(',', ';', ':', '-') + "…"
    }

    // ------------------------------------------------------------------------------------------
    // Trackers
    // ------------------------------------------------------------------------------------------

    private val IMG_TAG = Regex("(?i)<img\\b[^>]*>")
    private val SRC = Regex("(?i)\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']")
    private val WIDTH1 = Regex("(?i)\\b(width|height)\\s*=\\s*[\"']?\\s*[01](px)?\\s*[\"']?")
    private val STYLE_TINY = Regex("(?i)style\\s*=\\s*[\"'][^\"']*(width|height)\\s*:\\s*[01]px")
    private val STYLE_HIDDEN = Regex("(?i)style\\s*=\\s*[\"'][^\"']*(display\\s*:\\s*none|visibility\\s*:\\s*hidden|opacity\\s*:\\s*0[;\"'])")

    /** Host or path fragments that belong to open-tracking services. Matched case-insensitively. */
    private val TRACKER_HINTS = listOf(
        "/track/open", "/wf/open", "/open.php", "/o.gif", "/open.gif", "/pixel", "pixel.", "/beacon", "/t.gif",
        "list-manage.com/track", "mailtrack.io", "mailtrack", "t.hubspotemail.net", "hubspotlinks", "track.hubspot",
        "sendgrid.net/wf", "mandrillapp.com/track", "mailchimp", "mcusercontent", "exacttarget", "sfmc-content",
        "cmail19.com", "cmail20.com", "createsend", "mixmax.com", "yesware", "bananatag", "streak.com", "getnotify",
        "superhuman.com", "mailsuite", "trk.", "email.mg.", "/e/o/", "emltrk", "mailstat", "klclick", "klaviyo",
        "customeriomail", "intercom-mail", "convertkit", "ck.page", "substack.com/o/", "awstrack.me", "sparkpostmail",
        "mktdns", "mkt.com/trk", "salesloft", "outreach.io", "apollo.io",
    )

    /** How many images in [html] look like open-tracking pixels. */
    fun trackerCount(html: String): Int = IMG_TAG.findAll(html).count { isTracker(it.value) }

    fun isTracker(imgTag: String): Boolean {
        val src = SRC.find(imgTag)?.groupValues?.get(1)?.lowercase() ?: return false
        if (src.startsWith("cid:") || src.startsWith("data:")) return false
        val tiny = WIDTH1.findAll(imgTag).count() >= 1 || STYLE_TINY.containsMatchIn(imgTag)
        val hidden = STYLE_HIDDEN.containsMatchIn(imgTag)
        val hinted = TRACKER_HINTS.any { src.contains(it) }
        return (tiny && (hinted || src.contains('?') || src.length > 60)) || hidden && hinted || hinted && tiny
    }

    /**
     * HTML made safe to render in a WebView with JavaScript off: scripts, event handlers, forms,
     * iframes and meta refreshes removed; tracking pixels deleted outright; and, unless the reader
     * chose to load them, every remote image swapped for a placeholder so merely opening a message
     * reveals nothing about the reader.
     */
    fun sanitize(html: String, allowRemote: Boolean, cidResolver: (String) -> String? = { null }): String {
        var s = html
        s = Regex("(?is)<(script|iframe|object|embed|form|frame|frameset|applet|base)\\b[^>]*>.*?</\\1\\s*>").replace(s, "")
        s = Regex("(?is)<(script|iframe|object|embed|form|frame|base|link)\\b[^>]*/?>").replace(s, "")
        s = Regex("(?i)<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[^>]*>").replace(s, "")
        s = Regex("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)").replace(s, "")
        s = Regex("(?i)(href|src)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2").replace(s, "$1=\"#\"")
        s = IMG_TAG.replace(s) { m ->
            val tag = m.value
            val src = SRC.find(tag)?.groupValues?.get(1)
            when {
                isTracker(tag) -> ""
                src == null -> tag
                src.startsWith("cid:", true) -> cidResolver(src.substring(4).trim('<', '>'))?.let { tag.replace(src, it) } ?: tag
                src.startsWith("data:", true) -> tag
                allowRemote -> tag
                else -> tag.replace(src, "about:blank").replace(Regex("(?i)<img\\b"), "<img data-blocked-src=\"${src.replace("\"", "&quot;")}\"")
            }
        }
        if (!allowRemote) {
            // Remote CSS backgrounds load just like images do.
            s = Regex("(?i)url\\(\\s*['\"]?https?://[^)]*\\)").replace(s, "none")
            s = Regex("(?i)\\bbackground\\s*=\\s*[\"']https?://[^\"']*[\"']").replace(s, "")
        }
        return s
    }

    /** Remote images [sanitize] would block — the count the reader's "load images" affordance shows. */
    fun remoteImageCount(html: String): Int = IMG_TAG.findAll(html).count { m ->
        val src = SRC.find(m.value)?.groupValues?.get(1).orEmpty()
        (src.startsWith("http://", true) || src.startsWith("https://", true)) && !isTracker(m.value)
    }

    // ------------------------------------------------------------------------------------------
    // Structured data and links
    // ------------------------------------------------------------------------------------------

    private val JSON_LD = Regex("(?is)<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script\\s*>")

    /** schema.org JSON-LD blocks — airlines, hotels, shops and carriers embed them for mail clients. */
    fun jsonLd(html: String): List<String> = JSON_LD.findAll(html).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

    private val HREF = Regex("(?i)<a\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a\\s*>", RegexOption.DOT_MATCHES_ALL)

    data class Link(val url: String, val text: String)

    fun links(html: String): List<Link> = HREF.findAll(html).map {
        Link(decodeEntities(it.groupValues[1]).trim(), toText(it.groupValues[2]).take(120))
    }.toList()

    /** The in-body unsubscribe link, for senders that skip the List-Unsubscribe header. */
    fun unsubscribeLink(html: String): String? = links(html).firstOrNull { l ->
        val t = l.text.lowercase()
        val u = l.url.lowercase()
        (t.contains("unsubscribe") || t.contains("opt out") || t.contains("opt-out") || t.contains("abmelden") ||
            t.contains("se désabonner") || t.contains("désinscri") || t.contains("darse de baja") ||
            u.contains("unsubscribe") || u.contains("optout")) && (u.startsWith("http") || u.startsWith("mailto:"))
    }?.url

    /** Escape text for safe inclusion in generated HTML (reply quoting, plain-to-HTML). */
    fun escape(text: String): String = buildString(text.length + 16) {
        for (c in text) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> append(c)
        }
    }

    /** Plain text → minimal HTML (paragraphs and line breaks; URLs linked). */
    fun fromPlain(text: String): String {
        val escaped = escape(text)
        val linked = Regex("(https?://[^\\s<]+)").replace(escaped) { m -> "<a href=\"${m.value}\">${m.value}</a>" }
        return linked.split(Regex("\\n{2,}")).joinToString("") { "<p>" + it.replace("\n", "<br>") + "</p>" }
    }
}
