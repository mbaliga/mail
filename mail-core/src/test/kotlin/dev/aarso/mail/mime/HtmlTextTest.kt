package dev.aarso.mail.mime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test fun `html flattens to readable text`() {
        val html = """
            <html><head><style>p{color:red}</style><title>x</title></head>
            <body><h1>Order&nbsp;shipped</h1><p>Hi Ada,<br>your order <b>#1234</b> is on its way &mdash; ETA Friday.</p>
            <ul><li>Widget &times; 2</li><li>Gadget</li></ul><script>alert(1)</script>
            <table><tr><td>Total</td><td>&euro;42.00</td></tr></table></body></html>
        """.trimIndent()
        val text = HtmlText.toText(html)
        assertTrue(text, text.startsWith("Order shipped"))
        assertTrue(text.contains("Hi Ada,\nyour order #1234 is on its way — ETA Friday."))
        assertTrue(text.contains("• Widget × 2"))
        assertTrue(text.contains("Total €42.00"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("color:red"))
    }

    @Test fun `new content drops quoted history and signatures`() {
        val text = """
            Sounds good, see you then!

            Sent from my iPhone

            On Tue, Nov 3, 2026 at 2:05 PM Ada Lovelace <ada@example.org> wrote:
            > Shall we meet at 10?
            > Ada
        """.trimIndent()
        assertEquals("Sounds good, see you then!", HtmlText.newContent(text))
        val outlook = "Thanks!\n\nFrom: Bob <bob@x.com>\nSent: Monday\nTo: me\nSubject: hi\n\nold stuff"
        assertEquals("Thanks!", HtmlText.newContent(outlook))
        assertEquals("Only a forward", HtmlText.newContent("Only a forward"))
    }

    @Test fun `tracking pixels are detected and stripped, remote images blocked`() {
        val html = """
            <p>Hello</p>
            <img src="https://news.example.com/hero.jpg" width="600">
            <img src="https://example.list-manage.com/track/open.php?u=1&id=2" width="1" height="1">
            <img src="https://t.hubspotemail.net/e2t/to/abc" style="display:none">
            <img src="cid:logo@x">
        """.trimIndent()
        assertEquals(2, HtmlText.trackerCount(html))
        assertEquals(1, HtmlText.remoteImageCount(html))
        val safe = HtmlText.sanitize(html, allowRemote = false) { cid -> if (cid == "logo@x") "content://logo" else null }
        assertFalse(safe.contains("list-manage"))
        assertFalse(safe.contains("hubspotemail"))
        assertTrue(safe.contains("data-blocked-src=\"https://news.example.com/hero.jpg\""))
        assertTrue(safe.contains("content://logo"))
        assertTrue(HtmlText.sanitize(html, allowRemote = true).contains("src=\"https://news.example.com/hero.jpg\""))
    }

    @Test fun `scripts and handlers never survive sanitising`() {
        val safe = HtmlText.sanitize("""<a href="javascript:steal()" onclick="x()">x</a><iframe src="//evil"></iframe><script>bad()</script>""", true)
        assertFalse(safe.contains("javascript:"))
        assertFalse(safe.contains("onclick"))
        assertFalse(safe.contains("iframe"))
        assertFalse(safe.contains("bad()"))
    }

    @Test fun `json-ld and unsubscribe links are found`() {
        val html = """
            <script type="application/ld+json">{"@type":"FlightReservation","reservationNumber":"ABC123"}</script>
            <p><a href="https://shop.example.com/u?id=9">Unsubscribe</a></p>
        """.trimIndent()
        assertEquals(1, HtmlText.jsonLd(html).size)
        assertTrue(HtmlText.jsonLd(html)[0].contains("FlightReservation"))
        assertEquals("https://shop.example.com/u?id=9", HtmlText.unsubscribeLink(html))
    }

    @Test fun `preview is short and cut on a word`() {
        val p = HtmlText.preview("word ".repeat(100), 50)
        assertTrue(p.length <= 51)
        assertTrue(p.endsWith("…"))
    }
}
