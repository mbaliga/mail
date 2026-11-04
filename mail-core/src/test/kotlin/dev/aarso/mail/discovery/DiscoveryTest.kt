package dev.aarso.mail.discovery

import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.Security
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    @Test fun `presets cover the big providers`() {
        assertEquals("gmail", Providers.forDomain("gmail.com")!!.id)
        assertEquals("icloud", Providers.forDomain("me.com")!!.id)
        assertEquals("outlook", Providers.forDomain("hotmail.co.uk")!!.id)
        val icloud = Providers.ICLOUD
        assertEquals("imap.mail.me.com", icloud.imap!!.host)
        assertEquals(Security.STARTTLS, icloud.smtp!!.security)
        assertEquals(listOf(AuthKind.APP_PASSWORD), icloud.auth)
        assertEquals(IncomingProtocol.JMAP, Providers.FASTMAIL.protocol)
        assertFalse(Providers.TUTA.supported)
    }

    @Test fun `mx identifies hosted custom domains, most specific first`() {
        assertEquals("gmail", Providers.forMx("aspmx.l.google.com.")!!.id)
        assertEquals("m365", Providers.forMx("contoso-com.mail.protection.outlook.com")!!.id)
        assertEquals("icloud", Providers.forMx("mx01.mail.icloud.com")!!.id)
        assertEquals("fastmail", Providers.forMx("in1-smtp.messagingengine.com")!!.id)
    }

    @Test fun `clientConfig xml parses with substitutions`() {
        val xml = """
            <?xml version="1.0"?>
            <clientConfig version="1.1"><emailProvider id="example.com">
              <incomingServer type="pop3"><hostname>pop.example.com</hostname><port>995</port><socketType>SSL</socketType>
                <username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication></incomingServer>
              <incomingServer type="imap"><hostname>imap.example.com</hostname><port>993</port><socketType>SSL</socketType>
                <username>%EMAILLOCALPART%</username><authentication>password-cleartext</authentication></incomingServer>
              <outgoingServer type="smtp"><hostname>smtp.example.com</hostname><port>587</port><socketType>STARTTLS</socketType>
                <username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication></outgoingServer>
            </emailProvider></clientConfig>
        """.trimIndent()
        val r = AutoDiscovery().parseClientConfig(xml, "ada@example.com", DiscoverySource.ISPDB)
        assertEquals(IncomingProtocol.IMAP, r.first().protocol)
        assertEquals("imap.example.com", r.first().incoming!!.host)
        assertEquals("ada", r.first().username)
        assertEquals(587, r.first().smtp!!.port)
        assertEquals(IncomingProtocol.POP3, r[1].protocol)
    }

    @Test fun `custom domain on workspace is found through dns over https`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/resolve") && path.contains("type=MX") ->
                        MockResponse().setBody("""{"Status":0,"Answer":[{"name":"acme.io.","type":15,"data":"1 aspmx.l.google.com."}]}""")
                    path.startsWith("/resolve") -> MockResponse().setBody("""{"Status":3}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val d = AutoDiscovery(ispdbBase = server.url("/ispdb/").toString(), dohBase = server.url("/resolve").toString(), timeoutMs = 3000)
            val configs = d.discover("ceo@acme.io")
            assertEquals(DiscoverySource.MX, configs.first().source)
            assertEquals("gmail", configs.first().preset!!.id)
            assertEquals("ceo@acme.io", configs.first().username)
            assertTrue(configs.any { it.source == DiscoverySource.GUESS })
        } finally {
            server.shutdown()
        }
    }
}
