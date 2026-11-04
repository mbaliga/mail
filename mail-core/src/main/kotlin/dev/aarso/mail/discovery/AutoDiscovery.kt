package dev.aarso.mail.discovery

import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.Security
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

enum class DiscoverySource { PRESET, MX, ISPDB, AUTOCONFIG, SRV, GUESS }

/** One way to reach an address's mail, with where the knowledge came from. */
data class DiscoveredConfig(
    val source: DiscoverySource,
    val preset: ProviderPreset?,
    val protocol: IncomingProtocol,
    val incoming: ServerTemplate?,
    val smtp: ServerTemplate?,
    val jmapSessionUrl: String? = null,
    val username: String,
    val auth: List<AuthKind>,
    val oauthProvider: String? = null,
) {
    val displayName: String get() = preset?.name ?: incoming?.host ?: jmapSessionUrl ?: "Custom"
}

/**
 * Finds the servers for an address the way a careful person would, most authoritative first:
 *
 *  1. A built-in preset for the domain (gmail.com, icloud.com, …).
 *  2. The domain's MX records — a custom domain on Google Workspace, Microsoft 365, iCloud+, Fastmail
 *     or Zoho is recognised by where its mail is delivered, so `you@yourcompany.com` "just works".
 *  3. Mozilla's ISPDB (the database Thunderbird uses) and the domain's own autoconfig file.
 *  4. RFC 6186 SRV records and RFC 8620 JMAP discovery.
 *  5. Conventional guesses (imap.domain:993, smtp.domain:465/587), verified by connecting.
 *
 * DNS goes over HTTPS (DoH) because Android exposes no SRV/MX lookups to apps below API 29 and none
 * that work identically on the JVM test host.
 */
class AutoDiscovery(
    private val http: OkHttpClient = OkHttpClient(),
    private val ispdbBase: String = "https://autoconfig.thunderbird.net/v1.1/",
    private val dohBase: String = "https://dns.google/resolve",
    private val timeoutMs: Long = 8_000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(email: String): List<DiscoveredConfig> {
        val address = email.trim()
        val domain = address.substringAfterLast('@').lowercase()
        if (domain.isEmpty()) return emptyList()
        Providers.forDomain(domain)?.let { return listOf(fromPreset(it, address, DiscoverySource.PRESET)) }

        return coroutineScope {
            val mx = async { timed { mx(domain) } ?: emptyList() }
            val ispdb = async { timed { ispdb(domain, address) } ?: emptyList() }
            val auto = async { timed { domainAutoconfig(domain, address) } ?: emptyList() }
            val srv = async { timed { srv(domain, address) } ?: emptyList() }

            val out = ArrayList<DiscoveredConfig>()
            mx.await().firstNotNullOfOrNull { Providers.forMx(it) }?.let { out += fromPreset(it, address, DiscoverySource.MX) }
            listOf(ispdb, auto, srv).awaitAll().forEach { out += it }
            out += guesses(domain, address, mx.await())
            out.distinctBy { listOf(it.protocol, it.incoming?.host, it.incoming?.port, it.jmapSessionUrl) }
        }
    }

    fun fromPreset(p: ProviderPreset, email: String, source: DiscoverySource) = DiscoveredConfig(
        source = source,
        preset = p,
        protocol = p.protocol,
        incoming = p.imap ?: p.pop3,
        smtp = p.smtp,
        jmapSessionUrl = p.jmapSessionUrl,
        username = p.username(email),
        auth = p.auth,
        oauthProvider = p.oauthProvider,
    )

    private suspend fun <T> timed(block: suspend () -> T): T? =
        withTimeoutOrNull(timeoutMs) { runCatching { block() }.getOrNull() }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/xml, application/dns-json, application/json").get().build()
        http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    // ---- DNS over HTTPS ----

    /** MX hosts, best preference first. */
    suspend fun mx(domain: String): List<String> = doh(domain, "MX").mapNotNull { data ->
        val parts = data.trim().split(Regex("\\s+"))
        if (parts.size == 2) parts[0].toIntOrNull()?.let { it to parts[1].trimEnd('.') } else null
    }.sortedBy { it.first }.map { it.second }

    private suspend fun doh(name: String, type: String): List<String> {
        val body = get("$dohBase?name=${URLEncoder.encode(name, "UTF-8")}&type=$type") ?: return emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        return root["Answer"]?.jsonArray?.mapNotNull { it.jsonObject["data"]?.jsonPrimitive?.contentOrNull }.orEmpty()
    }

    private suspend fun srvRecord(name: String): ServerTemplate? {
        val records = doh(name, "SRV").mapNotNull { data ->
            val p = data.trim().split(Regex("\\s+"))
            if (p.size == 4) Triple(p[0].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0, p[3].trimEnd('.')) else null
        }.filter { it.third.isNotEmpty() && it.third != "." }
        val best = records.minByOrNull { it.first } ?: return null
        return ServerTemplate(best.third, best.second, Security.SSL_TLS)
    }

    private suspend fun srv(domain: String, email: String): List<DiscoveredConfig> {
        val imaps = srvRecord("_imaps._tcp.$domain")
        val imap = imaps ?: srvRecord("_imap._tcp.$domain")?.copy(security = Security.STARTTLS)
        val submissions = srvRecord("_submissions._tcp.$domain")
        val submission = submissions ?: srvRecord("_submission._tcp.$domain")?.copy(security = Security.STARTTLS)
        val jmap = srvRecord("_jmap._tcp.$domain")
        val out = ArrayList<DiscoveredConfig>()
        if (jmap != null) {
            out += DiscoveredConfig(DiscoverySource.SRV, null, IncomingProtocol.JMAP, null, submission,
                "https://${jmap.host}:${jmap.port}/.well-known/jmap", email, listOf(AuthKind.PASSWORD, AuthKind.API_TOKEN))
        }
        if (imap != null) {
            out += DiscoveredConfig(DiscoverySource.SRV, null, IncomingProtocol.IMAP, imap, submission, null, email, listOf(AuthKind.PASSWORD))
        }
        return out
    }

    // ---- Autoconfig XML (Thunderbird's clientConfig v1.1) ----

    private suspend fun ispdb(domain: String, email: String): List<DiscoveredConfig> =
        get(ispdbBase + domain)?.let { parseClientConfig(it, email, DiscoverySource.ISPDB) }.orEmpty()

    private suspend fun domainAutoconfig(domain: String, email: String): List<DiscoveredConfig> {
        val e = URLEncoder.encode(email, "UTF-8")
        val urls = listOf(
            "https://autoconfig.$domain/mail/config-v1.1.xml?emailaddress=$e",
            "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml?emailaddress=$e",
        )
        for (u in urls) {
            val body = runCatching { get(u) }.getOrNull() ?: continue
            val parsed = parseClientConfig(body, email, DiscoverySource.AUTOCONFIG)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    fun parseClientConfig(xml: String, email: String, source: DiscoverySource): List<DiscoveredConfig> {
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // No external entities: this XML comes from the network.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                isExpandEntityReferences = false
            }
            f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        }.getOrNull() ?: return emptyList()

        /** (type, server, username) per element — each server names its own username form. */
        fun servers(tag: String): List<Triple<String, ServerTemplate, String>> {
            val nodes = doc.getElementsByTagName(tag)
            return (0 until nodes.length).mapNotNull { i ->
                val el = nodes.item(i) as? Element ?: return@mapNotNull null
                val type = el.getAttribute("type").lowercase()
                val host = el.child("hostname")?.let { substitute(it, email) } ?: return@mapNotNull null
                val port = el.child("port")?.toIntOrNull() ?: return@mapNotNull null
                val sec = when (el.child("socketType")?.uppercase()) {
                    "SSL" -> Security.SSL_TLS
                    "STARTTLS" -> Security.STARTTLS
                    else -> Security.NONE
                }
                Triple(type, ServerTemplate(host, port, sec), el.child("username")?.let { substitute(it, email) } ?: email)
            }
        }

        val incoming = doc.getElementsByTagName("incomingServer")
        val authKinds = (0 until incoming.length).flatMap { i ->
            val el = incoming.item(i) as? Element ?: return@flatMap emptyList()
            val auths = el.getElementsByTagName("authentication")
            (0 until auths.length).map { auths.item(it).textContent.trim().lowercase() }
        }.mapNotNull {
            when (it) {
                "oauth2" -> AuthKind.OAUTH2
                "password-cleartext", "password-encrypted", "plain", "secure" -> AuthKind.PASSWORD
                else -> null
            }
        }.distinct().ifEmpty { listOf(AuthKind.PASSWORD) }

        val smtp = servers("outgoingServer").filter { it.first == "smtp" }.map { it.second }
            .sortedBy { if (it.security == Security.SSL_TLS) 0 else if (it.security == Security.STARTTLS) 1 else 2 }.firstOrNull()
        return servers("incomingServer")
            .filter { it.first == "imap" || it.first == "pop3" }
            // IMAP over POP, encrypted over plain.
            .sortedWith(compareBy({ if (it.first == "imap") 0 else 1 }, { if (it.second.security == Security.NONE) 1 else 0 }))
            .map { (type, server, username) ->
                DiscoveredConfig(source, null, if (type == "imap") IncomingProtocol.IMAP else IncomingProtocol.POP3,
                    server, smtp, null, username, authKinds)
            }
    }

    private fun Element.child(name: String): String? {
        val n = getElementsByTagName(name)
        return if (n.length > 0) n.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    private fun substitute(template: String, email: String): String = template
        .replace("%EMAILADDRESS%", email)
        .replace("%EMAILLOCALPART%", email.substringBefore('@'))
        .replace("%EMAILDOMAIN%", email.substringAfterLast('@'))

    // ---- Guesses ----

    private fun guesses(domain: String, email: String, mx: List<String>): List<DiscoveredConfig> {
        val hosts = buildList {
            add("imap.$domain"); add("mail.$domain")
            mx.firstOrNull()?.let { m -> m.substringAfter('.').takeIf { it.contains('.') }?.let { add("imap.$it") } }
        }.distinct()
        return hosts.map { h ->
            val smtpHost = h.replaceFirst("imap.", "smtp.")
            DiscoveredConfig(
                DiscoverySource.GUESS, null, IncomingProtocol.IMAP,
                ServerTemplate(h, 993, Security.SSL_TLS), ServerTemplate(smtpHost, 465, Security.SSL_TLS),
                null, email, listOf(AuthKind.PASSWORD),
            )
        }
    }
}
