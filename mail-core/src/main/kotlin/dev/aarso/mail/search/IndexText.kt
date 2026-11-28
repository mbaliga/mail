package dev.aarso.mail.search

import com.asystemofcells.search.QueryNode
import com.asystemofcells.search.Segmenter
import java.util.Locale

/**
 * What goes into the search index, and how a query is shaped to match it.
 *
 * SQLite's FTS5 tokenizer treats an unbroken Chinese, Japanese, Thai, Lao, Khmer or Myanmar run as
 * *one* word, and shatters Indic and pointed Arabic/Hebrew words at their vowel marks — so mail in
 * those scripts is unsearchable by anything but a whole sentence, or by half a word. search-core's
 * [Segmenter] does the word-breaking properly and hands the index pre-segmented, space-joined
 * tokens; this class is that seam for the mail store. Text with none of those scripts (everything
 * Latin, Cyrillic, Greek, Korean, …) goes in untouched, so snippets and highlights stay readable.
 *
 * The boundary rules are the host's to choose, once, at wiring time: the JDK's [Segmenter.defaultSource]
 * has no dictionary for lone Han/Thai runs, so the Android app injects an ICU-backed source
 * (`android.icu.text.BreakIterator`), which does. Because a different source breaks words
 * differently, [version] names it: change the source or [SCHEME] and the store re-indexes in the
 * background.
 */
class IndexText(
    private val boundary: Segmenter.BoundarySource = Segmenter.defaultSource,
    /** Names the boundary source in [version] ("jdk", "icu"). */
    private val sourceId: String = "jdk",
    private val locale: Locale = Locale.ROOT,
) {
    /** The index format the store holds; a different value means the stored index must be rebuilt. */
    val version: String get() = "$SCHEME:$sourceId"

    /** Does [text] hold anything the stock FTS5 tokenizer would mishandle? */
    fun needsSegmentation(text: String): Boolean {
        for (ch in text) {
            if (ch.code < 0x0590) continue // Latin, Greek, Cyrillic, Armenian: fine as they are
            if (isHard(ch)) return true
        }
        return false
    }

    /** The text as the index should hold it. */
    fun forIndex(text: String): String =
        if (text.isEmpty() || !needsSegmentation(text)) text else Segmenter.tokenizeForIndex(text, locale, boundary)

    /** A word or phrase the user typed, as the index would have broken it (null if nothing searchable remains). */
    fun forQuery(text: String): String? {
        if (!needsSegmentation(text)) return text
        return Segmenter.tokenizeForIndex(text, locale, boundary).ifBlank { null }
    }

    /** Rewrite a parsed query's words so they meet the index on its own terms. */
    fun rewrite(node: QueryNode?): QueryNode? = when (node) {
        null -> null
        is QueryNode.Term -> if (!needsSegmentation(node.text)) node else {
            val t = Segmenter.segment(node.text, locale, boundary).map { it.normalized }
            when (t.size) { 0 -> null; 1 -> QueryNode.Term(t[0], node.prefix); else -> QueryNode.Phrase(t.joinToString(" ")) }
        }
        is QueryNode.Phrase -> if (!needsSegmentation(node.text)) node else forQuery(node.text)?.let { QueryNode.Phrase(it) }
        is QueryNode.Semantic -> if (!needsSegmentation(node.text)) node else forQuery(node.text)?.let { QueryNode.Semantic(it) }
        is QueryNode.And -> node.children.mapNotNull(::rewrite).let { if (it.isEmpty()) null else if (it.size == 1) it[0] else QueryNode.And(it) }
        is QueryNode.Or -> node.children.mapNotNull(::rewrite).let { if (it.isEmpty()) null else if (it.size == 1) it[0] else QueryNode.Or(it) }
        is QueryNode.Not -> rewrite(node.child)?.let { QueryNode.Not(it) }
        else -> node
    }

    private fun isHard(ch: Char): Boolean {
        val s = Character.UnicodeScript.of(ch.code)
        return when (s) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.TIBETAN, Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI,
            Character.UnicodeScript.GUJARATI, Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU,
            Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM, Character.UnicodeScript.SINHALA -> true
            // Arabic and Hebrew words are fine until they carry vowel marks, which unicode61 treats as separators.
            Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW ->
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt()
            else -> false
        }
    }

    companion object {
        /** Bump when the shape of what is indexed changes (not just the boundary source). */
        const val SCHEME = 2
    }
}
