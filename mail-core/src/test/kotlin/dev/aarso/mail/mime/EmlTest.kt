package dev.aarso.mail.mime

import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class EmlTest {
    private val h = MessageHeader(
        accountId = 1, mailboxId = 1, messageId = "<abc@example.org>", inReplyTo = "<root@example.org>", references = listOf("<root@example.org>"),
        subject = "Re: Lease renewal — café", from = EmailAddress("priya@example.org", "Priya Raman"),
        to = listOf(EmailAddress("me@example.org", "Me")), cc = listOf(EmailAddress("marcus@example.org")),
        sentAt = 1_793_534_400_000L, receivedAt = 1_793_534_460_000L,
    )

    @Test fun `an eml round-trips through the parser with its headers intact`() {
        val bytes = Eml.build(h, MessageBody(1, text = "Thursday at 3 works.\nBring the lease — café."))
        val p = MimeParser.parse(bytes)
        assertEquals("Re: Lease renewal — café", p.subject)
        assertEquals("priya@example.org", p.from?.address)
        assertEquals("Priya Raman", p.from?.name)
        assertEquals(listOf("me@example.org"), p.to.map { it.address })
        assertEquals(listOf("marcus@example.org"), p.cc.map { it.address })
        assertEquals("abc@example.org", p.messageId?.trim('<', '>'))
        assertEquals("root@example.org", p.inReplyTo?.trim('<', '>'))
        assertEquals(1_793_534_400_000L, p.date)
        assertTrue(p.bestText()!!.contains("Bring the lease — café."))
    }

    @Test fun `html is kept as an alternative`() {
        val p = MimeParser.parse(Eml.build(h, MessageBody(1, text = "plain", html = "<p>rich <b>text</b></p>")))
        assertTrue(p.bestHtml()!!.contains("<b>text</b>"))
        assertEquals("plain", p.bestText()!!.trim())
    }

    @Test fun `a message with no stored body falls back to its preview`() {
        val p = MimeParser.parse(Eml.build(h.copy(preview = "Just the preview"), null))
        assertTrue(p.bestText()!!.contains("Just the preview"))
    }

    @Test fun `file names read well and are safe`() {
        val name = Eml.fileName(h.copy(subject = "RE: Fwd: Plans: 50% off? <now>"), ZoneId.of("UTC"))
        assertEquals("Plans_ 50% off_ _now_ — Priya Raman — 2026-11-01.eml", name)
        assertTrue(Eml.fileName(h.copy(subject = "x".repeat(500)), ZoneId.of("UTC")).length <= 115)
    }

    @Test fun `quoting orders a conversation oldest first`() {
        val a = h.copy(subject = "First", receivedAt = 1_000, from = EmailAddress("a@x.org", "A")) to MessageBody(1, text = "one\ntwo")
        val b = h.copy(subject = "Second", receivedAt = 2_000, from = EmailAddress("b@x.org", "B")) to MessageBody(2, text = "three")
        val q = Eml.quote(listOf(b, a), ZoneId.of("UTC"))
        assertTrue(q.indexOf("Subject: First") < q.indexOf("Subject: Second"))
        assertTrue(q.contains("> one\n> two"))
    }
}
