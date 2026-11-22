package dev.aarso.mail.sieve

import dev.aarso.mail.net.AuthenticationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManageSieveClientTest {
    private val servers = ArrayList<FakeManageSieveServer>()
    private fun server(vararg a: Any?, block: FakeManageSieveServer.() -> Unit = {}) = FakeManageSieveServer().also { servers += it; it.block() }
    @After fun stop() = servers.forEach { it.close() }

    private fun connect(s: FakeManageSieveServer, user: String = "me@example.org", pw: String = "secret") =
        ManageSieveClient.connect(s.factory(), "sieve.example", 4190, user, pw)

    @Test fun `discovers capabilities, upgrades to TLS, then signs in`() {
        val s = server()
        val c = connect(s)
        assertTrue("signed in only after the connection was secure", s.sawSecureAuth)
        assertEquals("Fake Pigeonhole", c.capabilities.implementation)
        assertEquals(setOf("fileinto", "imap4flags", "copy"), c.capabilities.sieve)
        assertEquals(setOf("PLAIN"), c.capabilities.sasl)
        assertFalse("STARTTLS is not offered again once secure", c.capabilities.startTls)
        assertEquals("1.0", c.capabilities.version)
        c.close()
    }

    @Test fun `the greeting alone shows what the server offers before anything is sent`() {
        val s = server()
        val t = s.factory().open("x", 1, dev.aarso.mail.model.Security.NONE)
        val c = ManageSieveClient(t)
        val caps = c.greeting()
        assertTrue(caps.startTls); assertTrue("no password sign-in is offered in the clear", caps.sasl.isEmpty())
        c.close()
    }

    @Test fun `a server without STARTTLS is refused and nothing is sent`() {
        val s = FakeManageSieveServer(offerStartTls = false).also { servers += it }
        try { connect(s); fail("should refuse") } catch (e: ManageSieveException) { assertTrue(e.message!!.contains("STARTTLS")) }
        assertTrue("no AUTHENTICATE went out", s.commands.none { it.startsWith("AUTHENTICATE") })
    }

    @Test fun `a wrong password is an authentication error`() {
        val s = server()
        try { connect(s, pw = "nope"); fail() } catch (e: AuthenticationException) { assertTrue(e.serverText!!.contains("failed")) }
    }

    @Test fun `scripts are listed, stored, read back, activated and deleted`() {
        val s = server()
        val c = connect(s)
        assertTrue(c.listScripts().isEmpty())
        val script = "require [\"fileinto\"];\r\n# “unicode” and \"quotes\" and a line that says OK\r\nOK\r\nif header :contains \"subject\" \"x\" { fileinto \"A\"; }\r\n"
        c.putScript("mine", script)
        c.putScript("OK", "keep;\r\n")          // a script that is *called* OK must not be mistaken for a status line
        assertEquals(script, c.getScript("mine"))
        c.setActive("mine")
        assertEquals(listOf(ManageSieveClient.Script("OK", false), ManageSieveClient.Script("mine", true)), c.listScripts())
        assertEquals("mine", s.active)
        try { c.deleteScript("mine"); fail() } catch (e: ManageSieveException) { assertTrue(e.text.contains("active")) }
        c.setActive("")
        assertNull(s.active)
        c.deleteScript("mine")
        assertEquals(listOf(ManageSieveClient.Script("OK", false)), c.listScripts())
        c.close()
    }

    @Test fun `a script the server cannot parse is reported with its own message`() {
        val s = server()
        val c = connect(s)
        try { c.putScript("bad", "syntaxerror here"); fail() } catch (e: ManageSieveException) { assertTrue(e.text.contains("line 3")) }
        assertTrue("nothing was stored", s.scripts.isEmpty())
        try { c.checkScript("syntaxerror"); fail() } catch (e: ManageSieveException) { assertTrue(e.text.contains("syntax")) }
        c.checkScript("keep;")
        assertTrue("checking stores nothing", s.scripts.isEmpty())
        c.close()
    }

    @Test fun `activating a script that does not exist is an error`() {
        val c = connect(server())
        try { c.setActive("ghost"); fail() } catch (e: ManageSieveException) { assertTrue(e.text.contains("no script")) }
        c.close()
    }

    @Test fun `a dropped connection is an error, not a hang`() {
        val s = server()
        val c = connect(s)
        s.close()
        c.close()
        try { c.listScripts(); fail() } catch (e: Exception) { /* any failure is fine; it must not block */ }
    }
}
