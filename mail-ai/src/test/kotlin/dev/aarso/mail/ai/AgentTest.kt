package dev.aarso.mail.ai

import dev.aarso.mail.model.Category
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {

    /** An engine that answers from a script, one reply per call, and remembers what it was shown. */
    private class Scripted(override val id: String, override val onDevice: Boolean, private val replies: List<String>) : LlmEngine {
        override val label = id
        override val maxInputTokens = 4000
        override val supportsStructuredOutput = false
        val prompts = ArrayList<String>()
        override suspend fun availability() = Availability.Ready
        override suspend fun generate(request: LlmRequest): LlmResponse {
            prompts += request.prompt
            val i = (prompts.size - 1).coerceAtMost(replies.lastIndex)
            return LlmResponse(replies[i], id, onDevice, 200, 40)
        }
    }

    private class Host(val mail: List<MessageHeader>) : AgentHost {
        val queries = ArrayList<String>()
        override suspend fun search(query: String, limit: Int): List<MessageHeader> { queries += query; return mail.take(limit) }
        override suspend fun count(query: String) = mail.size
        override suspend fun body(id: Long) = MessageBody(id, text = "Hello,\n\nCan you confirm Thursday? Call me on +44 20 7946 0958 or write to ada@example.org.\n\n> On Mon, you wrote:\n> earlier")
        override fun todayIso() = "2026-11-21"
    }

    private fun m(id: Long, subject: String, cat: Category, from: String = "News <news@example.com>", account: Long = 1, seen: Boolean = false) = MessageHeader(
        id = id, accountId = account, mailboxId = 1, subject = subject, from = EmailAddress(from.substringAfter('<').trimEnd('>').ifBlank { from }, from.substringBefore(" <")),
        receivedAt = 1_793_534_400_000L - id * 3_600_000L, category = cat, flags = if (seen) Flags.SEEN else 0, preview = "preview of $subject",
    )

    private val mail = listOf(
        m(1, "Weekly digest", Category.NEWSLETTERS), m(2, "Another digest", Category.NEWSLETTERS), m(3, "Sprint review Thursday?", Category.REPLIES, "Priya <priya@example.com>"),
        m(4, "Your statement is ready", Category.FINANCE, "Bank <bank@example.com>"),
    )

    private fun agent(engines: List<LlmEngine>, settings: AiSettings = AiSettings(), host: Host = Host(mail)) =
        MailAgent(Intelligence({ engines }, { settings }), host) to host

    @Test fun `searches, reads, and turns what it found into proposals`() = runBlocking {
        val local = Scripted("local", true, listOf(
            """{"tool":"search","query":"category:newsletters older_than:7d"}""",
            """{"tool":"propose","action":"archive","ids":[1,2],"title":"Archive 2 newsletters","why":"Read-later mail you never opened"}""",
            """{"tool":"read","id":3}""",
            """{"tool":"propose","action":"draft_reply","ids":[3],"text":"Thursday works for me."}""",
            """{"tool":"done","summary":"Two things to tidy and one reply to send."}""",
        ))
        val (a, _) = agent(listOf(local))
        val events = ArrayList<AgentEvent>()
        val run = a.run("tidy up", onEvent = { events += it })!!
        assertEquals(2, run.proposals.size)
        assertEquals(ActionKind.ARCHIVE, run.proposals[0].kind)
        assertEquals(listOf(1L, 2L), run.proposals[0].ids)
        assertEquals(ActionKind.DRAFT_REPLY, run.proposals[1].kind)
        assertEquals("Thursday works for me.", run.proposals[1].arg)
        assertTrue(run.onDevice)
        assertEquals("Two things to tidy and one reply to send.", run.summary)
        assertTrue(events.any { it is AgentEvent.Searching })
        assertTrue(events.any { it is AgentEvent.Proposed })
    }

    @Test fun `it can only act on messages it was shown, and is told when it tries otherwise`() = runBlocking {
        val local = Scripted("local", true, listOf(
            """{"tool":"propose","action":"trash","ids":[999]}""",
            """{"tool":"search","query":"is:unread"}""",
            """{"tool":"propose","action":"star","ids":[3]}""",
            """{"tool":"done","summary":"ok"}""",
        ))
        val (a, _) = agent(listOf(local))
        val run = a.run("x")!!
        assertEquals("the invented id never becomes a proposal", listOf(ActionKind.STAR), run.proposals.map { it.kind })
        assertTrue("the model was told why", local.prompts[1].contains("SYSTEM:"))
    }

    @Test fun `a proposal can name a query instead of ids`() = runBlocking {
        val local = Scripted("local", true, listOf("""{"tool":"propose","action":"archive","query":"category:newsletters"}""", """{"tool":"done","summary":"ok"}"""))
        val (a, host) = agent(listOf(local))
        val run = a.run("x")!!
        assertEquals(listOf("category:newsletters"), host.queries)
        assertEquals(mail.map { it.id }, run.proposals.single().ids)
    }

    @Test fun `tolerates code fences and chatter around the JSON`() = runBlocking {
        val local = Scripted("local", true, listOf("Sure! Here you go:\n```json\n{\"tool\":\"done\",\"summary\":\"All quiet.\"}\n```\nAnything else?"))
        val (a, _) = agent(listOf(local))
        assertEquals("All quiet.", a.run("x")!!.summary)
    }

    @Test fun `a local model that cannot produce steps hands over to the cloud key, but only when allowed`() = runBlocking {
        val garbage = Scripted("local", true, listOf("I think you should archive things."))
        val cloud = Scripted("cloud", false, listOf("""{"tool":"done","summary":"Cloud says hi."}"""))
        val allowed = AiSettings(cloudEngineId = "cloud", cloudTasks = setOf(AiTask.AGENT))

        val (a, _) = agent(listOf(garbage, cloud), allowed)
        val run = a.run("x")!!
        assertFalse(run.onDevice)
        assertEquals("Cloud says hi.", run.summary)

        val (b, _) = agent(listOf(Scripted("local", true, listOf("nonsense")), cloud), allowed)
        assertNull("background runs never go to the cloud", b.run("x", allowCloud = false))

        val (c, _) = agent(listOf(Scripted("local", true, listOf("nonsense")), cloud), AiSettings(cloudEngineId = "cloud"))
        assertNull("the cloud key is not used for a task the person has not allowed", c.run("x"))
    }

    @Test fun `what a cloud model is shown leaves out protected mail and redacts identifiers`() = runBlocking {
        val cloud = Scripted("cloud", false, listOf(
            """{"tool":"search","query":"in:inbox"}""", """{"tool":"read","id":3}""", """{"tool":"read","id":4}""", """{"tool":"done","summary":"ok"}""",
        ))
        val local = Scripted("local", true, listOf("garbage"))
        val (a, _) = agent(listOf(local, cloud), AiSettings(cloudEngineId = "cloud", cloudTasks = setOf(AiTask.AGENT)))
        val run = a.run("x")!!
        assertFalse(run.onDevice)
        val seenByCloud = cloud.prompts.joinToString("\n")
        assertTrue(seenByCloud.contains("Sprint review"))
        assertFalse("finance mail is withheld", seenByCloud.contains("Your statement is ready"))
        assertTrue(seenByCloud.contains("1 private message"))
        assertFalse("the phone number is redacted", seenByCloud.contains("7946 0958"))
        assertFalse("the address is redacted", seenByCloud.contains("ada@example.org"))
        assertTrue("it could not read what it was never shown", seenByCloud.contains("you can only read messages that a search showed you"))
    }

    @Test fun `on-device sees everything`() = runBlocking {
        val local = Scripted("local", true, listOf("""{"tool":"search","query":"in:inbox"}""", """{"tool":"done","summary":"ok"}"""))
        val (a, _) = agent(listOf(local))
        a.run("x")
        assertTrue(local.prompts.last().contains("Your statement is ready"))
    }

    @Test fun `it stops after a bounded number of steps`() = runBlocking {
        val local = Scripted("local", true, listOf("""{"tool":"count","query":"in:inbox"}"""))
        val (a, _) = agent(listOf(local))
        val run = a.run("x")!!
        assertEquals(MailAgent.MAX_STEPS, local.prompts.size)
        assertNotNull(run.summary)
        assertTrue(run.proposals.isEmpty())
    }

    @Test fun `snooze, label and rules need their extra fields`() = runBlocking {
        val local = Scripted("local", true, listOf(
            """{"tool":"search","query":"x"}""",
            """{"tool":"propose","action":"snooze","ids":[1]}""",
            """{"tool":"propose","action":"snooze","ids":[1],"until":"2026-11-23T09:00"}""",
            """{"tool":"propose","action":"make_rule","query":"from:news@example.com","rule_action":"archive"}""",
            """{"tool":"done","summary":"ok"}""",
        ))
        val (a, _) = agent(listOf(local))
        val run = a.run("x")!!
        assertEquals(listOf(ActionKind.SNOOZE, ActionKind.MAKE_RULE), run.proposals.map { it.kind })
        assertEquals("2026-11-23T09:00", run.proposals[0].arg)
        assertEquals("from:news@example.com", run.proposals[1].query)
    }
}
