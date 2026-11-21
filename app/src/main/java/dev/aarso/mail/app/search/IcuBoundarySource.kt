package dev.aarso.mail.app.search

import dev.aarso.search.Segmenter
import java.util.Locale

/**
 * Word boundaries from the phone's own ICU (`android.icu.text.BreakIterator`), which — unlike the JDK's
 * — carries dictionaries for Chinese, Japanese, Thai, Lao, Khmer and Myanmar. This is the seam
 * search-core's [Segmenter] leaves for the host; the mail index and queries both go through it.
 */
class IcuBoundarySource : Segmenter.BoundarySource {
    override fun boundaries(text: String, locale: Locale): List<Int> {
        val it = android.icu.text.BreakIterator.getWordInstance(locale)
        it.setText(text)
        val out = ArrayList<Int>()
        var b = it.first()
        out.add(b)
        while (b != android.icu.text.BreakIterator.DONE) {
            val next = it.next()
            if (next == android.icu.text.BreakIterator.DONE) break
            out.add(next)
            b = next
        }
        if (out.last() != text.length) out.add(text.length)
        return out
    }
}
