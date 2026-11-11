package dev.aarso.mail.app

import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.DraftKind
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Identity
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.SecretRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class DraftsTest {

    private val me = EmailAddress("mi@example.com", "Mi")
    private val alias = EmailAddress("mi@work.example", "Mi")
    private val account = Account(
        id = 1, email = me.address, displayName = "Mi", providerId = "custom", protocol = IncomingProtocol.IMAP,
        auth = AuthKind.PASSWORD, secret = SecretRef("x"),
        identities = listOf(Identity(me, signature = "Mi", isDefault = true), Identity(alias)),
    )
    private val priya = EmailAddress("priya@example.org", "Priya Raman")
    private val marcus = EmailAddress("marcus@example.org", "Marcus Lee")
    private val list = EmailAddress("team@lists.example.org")

    private fun header(from: EmailAddress, to: List<EmailAddress>, cc: List<EmailAddress> = emptyList(), replyTo: List<EmailAddress> = emptyList()) = MessageHeader(
        id = 7, accountId = 1, mailboxId = 1, messageId = "<abc@example.org>", references = listOf("<root@example.org>"),
        subject = "Re: Fwd: Lease", from = from, to = to, cc = cc, replyTo = replyTo, sentAt = 1_700_000_000_000, receivedAt = 1_700_000_000_000,
    )

    @Test fun replyGoesToTheSenderAndThreads() {
        val h = header(priya, listOf(me), cc = listOf(marcus))
        val d = Drafts.reply(account, h, MessageBody(7, text = "Thursday works.\nBring the lease."), all = false, zone = ZoneId.of("UTC"))
        assertEquals(listOf(priya), d.to)
        assertTrue(d.cc.isEmpty())
        assertEquals("Re: Lease", d.subject)
        assertEquals("<abc@example.org>", d.inReplyTo)
        assertEquals(listOf("<root@example.org>", "<abc@example.org>"), d.references)
        assertEquals(DraftKind.REPLY, d.kind)
        assertTrue(d.textBody.contains("> Thursday works.\n> Bring the lease."))
        assertTrue("signature comes before the quote", d.textBody.indexOf("-- \nMi") < d.textBody.indexOf("> Thursday"))
    }

    @Test fun replyAllKeepsEveryoneButMe() {
        val h = header(priya, listOf(me, marcus), cc = listOf(list, me))
        val d = Drafts.reply(account, h, null, all = true)
        assertEquals(listOf(priya), d.to)
        assertEquals(listOf(marcus, list), d.cc)
        assertEquals(DraftKind.REPLY_ALL, d.kind)
    }

    @Test fun replyToHeaderWins() {
        val h = header(priya, listOf(list), replyTo = listOf(list))
        assertEquals(listOf(list), Drafts.reply(account, h, null, all = false).to)
    }

    @Test fun replyingToMyOwnMessageContinuesToItsRecipients() {
        val h = header(me, listOf(priya, marcus))
        assertEquals(listOf(priya, marcus), Drafts.reply(account, h, null, all = false).to)
    }

    @Test fun replyComesFromTheAddressTheMailReached() {
        val h = header(priya, listOf(alias))
        assertEquals(alias.address, Drafts.reply(account, h, null, all = false).from.address)
    }

    @Test fun forwardCarriesHeadersAndAttachments() {
        val h = header(priya, listOf(me))
        val d = Drafts.forward(account, h, MessageBody(7, text = "See attached."), emptyList(), ZoneId.of("UTC"))
        assertEquals("Fwd: Lease", d.subject)
        assertTrue(d.to.isEmpty())
        assertTrue(d.textBody.contains("---------- Forwarded message ----------"))
        assertTrue(d.textBody.contains("From: Priya Raman <priya@example.org>"))
        assertTrue(d.textBody.contains("See attached."))
        assertEquals(DraftKind.FORWARD, d.kind)
    }

    @Test fun mailtoUris() {
        val d = Drafts.fromMailto(account, "mailto:a@example.com,b@example.com?cc=c@example.com&subject=Hello%20there&body=Line%201%0ALine%202")
        assertEquals(listOf("a@example.com", "b@example.com"), d.to.map { it.address })
        assertEquals(listOf("c@example.com"), d.cc.map { it.address })
        assertEquals("Hello there", d.subject)
        assertTrue(d.textBody.startsWith("Line 1\nLine 2"))
        // A literal plus is a plus in mailto:, not a space.
        assertEquals("a+tag@example.com", Drafts.fromMailto(account, "mailto:a+tag@example.com").to.single().address)
    }

    @Test fun baseSubjectStripsEveryPrefix() {
        assertEquals("Lease", Drafts.baseSubject("RE: Fwd: AW: re[2]: Lease"))
        assertEquals("Lease", Drafts.baseSubject("Lease"))
    }
}
