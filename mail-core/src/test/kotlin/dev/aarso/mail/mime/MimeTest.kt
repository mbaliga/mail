package dev.aarso.mail.mime

import dev.aarso.mail.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTest {

    private fun msg(s: String) = s.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)

    @Test fun `encoded words decode across charsets and adjacent words join`() {
        assertEquals("Grüße aus Köln", EncodedWords.decode("=?UTF-8?B?R3LDvMOfZQ==?= aus =?iso-8859-1?Q?K=F6ln?="))
        // RFC 2047 §6.2: whitespace *between two adjacent* encoded words is dropped, not kept.
        assertEquals("Grüßeaus Köln", EncodedWords.decode("=?UTF-8?B?R3LDvMOfZQ==?= =?iso-8859-1?Q?aus_K=F6ln?="))
        // Whitespace between two adjacent encoded words is folding, not content.
        assertEquals("日本語", EncodedWords.decode("=?UTF-8?B?5pel?= =?UTF-8?B?5pys6Kqe?="))
        assertEquals("plain text", EncodedWords.decode("plain text"))
        assertEquals("Re: Ünïcode tail", EncodedWords.decode("Re: =?utf-8?q?=C3=9Cn=C3=AFcode?= tail"))
    }

    @Test fun `encoded word encode round-trips long non-ascii`() {
        val s = "Ünïcödé subject that is long enough to need splitting into several encoded words — 日本語も"
        val enc = EncodedWords.encode(s)
        assertTrue(enc.split("\r\n ").all { it.length <= 75 })
        assertEquals(s, EncodedWords.decode(enc.replace("\r\n ", " ")))
    }

    @Test fun `addresses parse names, groups, comments and garbage`() {
        val list = AddressParser.parseList(
            "\"Lovelace, Ada\" <ada@example.org>, =?UTF-8?Q?J=C3=BCrgen?= <j@x.de>, bob@y.com (Bob B), undisclosed-recipients:;, <c@z.io>"
        )
        assertEquals(
            listOf(
                EmailAddress("ada@example.org", "Lovelace, Ada"),
                EmailAddress("j@x.de", "Jürgen"),
                EmailAddress("bob@y.com", "Bob B"),
                EmailAddress("c@z.io", null),
            ),
            list,
        )
        assertEquals(EmailAddress("john@x.com", "John Smith"), AddressParser.parseOne("John Smith john@x.com"))
        assertNull(AddressParser.parseOne("not an address"))
    }

    @Test fun `dates parse obsolete forms`() {
        val base = MailDates.parse("Tue, 3 Nov 2026 14:05:09 +0000")!!
        assertEquals(base, MailDates.parse("3 Nov 2026 09:05:09 EST"))
        assertEquals(base, MailDates.parse("Tue, 03 Nov 26 14:05:09 GMT"))
        assertEquals(base - 9_000, MailDates.parse("Tue, 3 Nov 2026 14:05 +0000 (UTC)"))
        assertNotNull(MailDates.parse("2026-11-03T14:05:09Z"))
        assertNull(MailDates.parse("sometime last week"))
    }

    @Test fun `rfc 2231 continuation parameters decode`() {
        val (v, p) = HeaderParams.split("attachment; filename*0*=UTF-8''%E2%9C%93%20Rech; filename*1*=nung.pdf; size=10")
        assertEquals("attachment", v)
        assertEquals("✓ Rechnung.pdf", p["filename"])
        assertEquals("10", p["size"])
    }

    @Test fun `simple quoted-printable message`() {
        val m = MimeParser.parse(
            msg(
                """
                From: =?UTF-8?Q?Ren=C3=A9e?= <renee@example.com>
                To: me@example.org
                Subject: =?UTF-8?B?Q2Fmw6k=?= tomorrow?
                Date: Tue, 3 Nov 2026 14:05:09 +0100
                Message-ID: <abc@example.com>
                Content-Type: text/plain; charset=utf-8
                Content-Transfer-Encoding: quoted-printable

                Shall we meet at the caf=C3=A9 at 10?=20
                It's a long line that was soft=
                 broken.
                """
            )
        )
        assertEquals("Café tomorrow?", m.subject)
        assertEquals(EmailAddress("renee@example.com", "Renée"), m.from)
        assertEquals("abc@example.com", m.messageId)
        val text = m.bestText()!!
        assertTrue(text.contains("café at 10? "))
        assertTrue(text.contains("soft broken."))
    }

    @Test fun `nested multipart numbers parts like imap and finds bodies and attachments`() {
        val m = MimeParser.parse(
            msg(
                """
                Subject: nested
                Content-Type: multipart/mixed; boundary="outer"

                preamble is ignored
                --outer
                Content-Type: multipart/related; boundary="rel"

                --rel
                Content-Type: multipart/alternative; boundary="alt"

                --alt
                Content-Type: text/plain; charset=us-ascii

                Plain body
                --alt
                Content-Type: text/html; charset=utf-8

                <p>HTML <b>body</b> <img src="cid:logo@x"></p>
                --alt--
                --rel
                Content-Type: image/png
                Content-ID: <logo@x>
                Content-Transfer-Encoding: base64

                iVBORw0KGgo=
                --rel--
                --outer
                Content-Type: application/pdf; name="invoice.pdf"
                Content-Disposition: attachment; filename="invoice.pdf"
                Content-Transfer-Encoding: base64

                JVBERi0xLjQK
                --outer--
                epilogue
                """
            )
        )
        assertEquals("Plain body", m.bestText())
        assertTrue(m.bestHtml()!!.contains("<b>body</b>"))
        val ids = m.root.walk().filter { !it.contentType.isMultipart }.map { it.partId to it.contentType.mimeType }.toList()
        assertEquals(
            listOf("1.1.1" to "text/plain", "1.1.2" to "text/html", "1.2" to "image/png", "2" to "application/pdf"),
            ids,
        )
        val att = m.attachments()
        assertEquals(1, att.size)
        assertEquals("invoice.pdf", att[0].fileName)
        assertEquals("%PDF-1.4\n", String(att[0].body))
        assertNotNull(m.inlineByCid()["logo@x"])
    }

    @Test fun `missing close delimiter still yields the last part`() {
        val m = MimeParser.parse(
            msg(
                """
                Content-Type: multipart/alternative; boundary=b

                --b
                Content-Type: text/plain

                one
                --b
                Content-Type: text/html

                <i>two</i>
                """
            )
        )
        assertEquals("one", m.bestText())
        assertTrue(m.bestHtml()!!.contains("<i>two</i>"))
    }

    @Test fun `embedded message rfc822 parts number beneath their container`() {
        val m = MimeParser.parse(
            msg(
                """
                Content-Type: multipart/mixed; boundary=x

                --x
                Content-Type: text/plain

                see attached
                --x
                Content-Type: message/rfc822

                Subject: inner
                Content-Type: text/plain

                inner body
                --x--
                """
            )
        )
        val inner = m.root.children[1]
        assertTrue(inner.isAttachment)
        assertEquals("2", inner.partId)
        assertEquals("2.1", inner.embedded!!.partId)
        assertEquals("inner body", inner.embedded!!.text().trim())
    }

    @Test fun `base64 tolerates line breaks junk and padding mid stream`() {
        assertEquals("Hello, World!", String(TransferEncoding.decodeBase64("SGVs\r\nbG8s IFdv\ncmxkIQ==".toByteArray())))
        // Two independently padded chunks.
        assertEquals("AB", String(TransferEncoding.decodeBase64("QQ==Qg==".toByteArray())))
        assertEquals("", String(TransferEncoding.decodeBase64("!!!".toByteArray())))
    }

    @Test fun `quoted printable keeps stray equals and decodes soft breaks`() {
        assertEquals("a=b c", String(TransferEncoding.decodeQuotedPrintable("a=b =\r\nc".toByteArray())))
        val qp = TransferEncoding.encodeQuotedPrintable("x".repeat(100) + " ünï =")
        assertTrue(qp.split("\r\n").all { it.length <= 76 })
        assertEquals("x".repeat(100) + " ünï =", String(TransferEncoding.decodeQuotedPrintable(qp.toByteArray()), Charsets.UTF_8))
    }

    @Test fun `format flowed rejoins soft breaks and keeps quotes`() {
        val text = "This is a soft \r\nbroken line.\r\n> quoted soft \r\n> part\r\nHard\r\n-- \r\nsig"
        assertEquals("This is a soft broken line.\n> quoted soft part\nHard\n-- \nsig", Flowed.decode(text, delSp = false))
    }

    @Test fun `charsets resolve aliases and never throw`() {
        assertEquals("windows-1252", MailCharsets.forName("iso-8859-1").name())
        assertEquals("UTF-8", MailCharsets.forName("utf8").name())
        assertEquals("UTF-8", MailCharsets.forName("x-totally-unknown").name())
        assertEquals("“quote”", MailCharsets.decode(byteArrayOf(0x93.toByte()) + "quote".toByteArray() + byteArrayOf(0x94.toByte()), "iso-8859-1"))
    }

    @Test fun `builder output parses back faithfully`() {
        val out = OutgoingMessage(
            from = EmailAddress("me@example.org", "Mé Myself"),
            to = listOf(EmailAddress("you@example.com", "Smith, John")),
            bcc = listOf(EmailAddress("secret@example.net")),
            subject = "Привет — résumé attached",
            text = "Line one\n.leading dot\nLine three",
            html = "<p>Line one</p>",
            parts = listOf(OutgoingPart("résumé.pdf", "application/pdf", "%PDF-1.7".toByteArray())),
            inReplyTo = "parent@example.com",
            references = listOf("root@example.com", "parent@example.com"),
            dateMillis = 1_793_000_000_000,
            messageId = MimeBuilder.newMessageId("me@example.org", 1_793_000_000_000),
        )
        val bytes = MimeBuilder.build(out)
        val raw = String(bytes)
        assertFalse("Bcc must not be on the wire", raw.contains("Bcc:"))
        assertTrue(raw.lines().all { it.length <= 998 })
        val p = MimeParser.parse(bytes)
        assertEquals("Привет — résumé attached", p.subject)
        assertEquals(EmailAddress("you@example.com", "Smith, John"), p.to.single())
        assertEquals("Mé Myself", p.from!!.name)
        assertEquals("parent@example.com", p.inReplyTo)
        assertEquals(listOf("root@example.com", "parent@example.com"), p.references)
        assertEquals("Line one\n.leading dot\nLine three", p.bestText()!!.replace("\r\n", "\n"))
        assertEquals("résumé.pdf", p.attachments().single().fileName)
        assertEquals("%PDF-1.7", String(p.attachments().single().body))
        assertTrue(MimeBuilder.build(out, includeBcc = true).toString(Charsets.UTF_8).contains("Bcc: secret@example.net"))
    }
}
