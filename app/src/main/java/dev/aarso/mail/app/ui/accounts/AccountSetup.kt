@file:OptIn(ExperimentalLayoutApi::class)

package dev.aarso.mail.app.ui.accounts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.platform.ConsentNeeded
import dev.aarso.mail.app.platform.ConsentRequiredException
import dev.aarso.mail.app.platform.Work
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.LocalBridge
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.Page
import dev.aarso.mail.app.ui.components.ProvenanceMark
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.discovery.DiscoveredConfig
import dev.aarso.mail.discovery.DiscoverySource
import dev.aarso.mail.discovery.Providers
import dev.aarso.mail.discovery.ServerTemplate
import androidx.compose.runtime.LaunchedEffect
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Identity
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.model.Security
import dev.aarso.mail.model.ServerEndpoint
import dev.aarso.mail.net.AuthenticationException
import dev.aarso.mail.net.CertificateException
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Adding an account, for any provider: type the address and the right servers are found (built-in
 * presets, the provider's own autoconfig, Thunderbird's database, DNS SRV and MX); then the sign-in
 * that provider actually supports — Google's or Microsoft's own consent screen, an app password
 * with the exact steps to make one (iCloud, Yahoo, AOL…), a Fastmail API token, or a password —
 * and every server setting stays editable for anything unusual (a company server, Proton Bridge).
 */
@Composable
fun AccountSetupScreen(reauthAccountId: Long?, onDone: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val bridge = LocalBridge.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var configs by remember { mutableStateOf<List<DiscoveredConfig>>(emptyList()) }
    var chosen by remember { mutableStateOf<DiscoveredConfig?>(null) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var auth by remember { mutableStateOf(AuthKind.PASSWORD) }

    // Manual / editable server settings.
    var protocol by remember { mutableStateOf(IncomingProtocol.IMAP) }
    var inHost by remember { mutableStateOf("") }
    var inPort by remember { mutableStateOf("993") }
    var inSec by remember { mutableStateOf(Security.SSL_TLS) }
    var outHost by remember { mutableStateOf("") }
    var outPort by remember { mutableStateOf("587") }
    var outSec by remember { mutableStateOf(Security.STARTTLS) }
    var user by remember { mutableStateOf("") }
    var jmapUrl by remember { mutableStateOf("") }

    fun choose(c: DiscoveredConfig) {
        chosen = c
        protocol = c.protocol
        c.incoming?.let { inHost = it.host; inPort = it.port.toString(); inSec = it.security }
        c.smtp?.let { outHost = it.host; outPort = it.port.toString(); outSec = it.security }
        user = c.username
        jmapUrl = c.jmapSessionUrl.orEmpty()
        auth = c.auth.firstOrNull { it != AuthKind.OAUTH2 || oauthReady(c.oauthProvider, g) } ?: c.auth.firstOrNull() ?: AuthKind.PASSWORD
        if (auth == AuthKind.OAUTH2 && !oauthReady(c.oauthProvider, g)) auth = AuthKind.APP_PASSWORD
    }

    var existing by remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(reauthAccountId) {
        val a = reauthAccountId?.let { g.store.account(it) } ?: return@LaunchedEffect
        existing = a
        email = a.email; name = a.displayName
        val preset = Providers.byId(a.providerId)
        val c = DiscoveredConfig(
            source = DiscoverySource.PRESET, preset = preset, protocol = a.protocol,
            incoming = a.incoming?.let { ServerTemplate(it.host, it.port, it.security) },
            smtp = a.outgoing?.let { ServerTemplate(it.host, it.port, it.security) },
            jmapSessionUrl = a.jmapSessionUrl, username = a.incoming?.username ?: a.email,
            auth = preset?.auth ?: listOf(a.auth), oauthProvider = a.oauthProvider ?: preset?.oauthProvider,
        )
        configs = listOf(c)
        choose(c)
        auth = a.auth
    }

    fun buildAccount(): Account {
        val preset = chosen?.preset
        val ref = existing?.secret ?: SecretRef("acct." + UUID.randomUUID())
        val useJmap = protocol == IncomingProtocol.JMAP && (auth == AuthKind.API_TOKEN || auth == AuthKind.OAUTH2 || preset?.protocol == IncomingProtocol.JMAP && auth != AuthKind.APP_PASSWORD)
        val proto = if (protocol == IncomingProtocol.JMAP && !useJmap) IncomingProtocol.IMAP else protocol
        val base = existing
        return (base ?: Account(email = email.trim(), displayName = "", providerId = "custom", protocol = IncomingProtocol.IMAP, auth = auth, secret = ref)).copy(
            email = email.trim(),
            displayName = name.trim().ifBlank { email.substringBefore('@') },
            providerId = preset?.id ?: "custom",
            protocol = proto,
            incoming = if (proto == IncomingProtocol.JMAP) null else ServerEndpoint(inHost.trim(), inPort.toIntOrNull() ?: 993, inSec, user.trim().ifBlank { email.trim() }),
            outgoing = if (proto == IncomingProtocol.JMAP || outHost.isBlank()) null else ServerEndpoint(outHost.trim(), outPort.toIntOrNull() ?: 587, outSec, user.trim().ifBlank { email.trim() }),
            jmapSessionUrl = jmapUrl.trim().ifBlank { null },
            auth = auth,
            oauthProvider = if (auth == AuthKind.OAUTH2) chosen?.oauthProvider else null,
            secret = ref,
            identities = base?.identities?.takeIf { it.isNotEmpty() } ?: listOf(Identity(EmailAddress(email.trim(), name.trim().ifBlank { null }), isDefault = true)),
        )
    }

    suspend fun finish(account: Account) {
        busy = "Checking"
        error = null
        try {
            g.connector.verify(account)
            val first = g.accountsNow().isEmpty()
            val saved = g.store.saveAccount(account)
            g.updateSettings { it.copy(onboarded = true) }
            if (first) bridge.requestNotifications()
            busy = "Fetching your mail"
            g.scope.launch { g.sync.sync(saved, inboxOnly = true); g.sync.sync(saved) }
            Work.syncNow(g.context, saved.id)
            onDone()
        } catch (e: ConsentRequiredException) {
            error = "Confirm access on the next screen"
        } catch (e: AuthenticationException) {
            error = "The server didn't accept that. " + (e.serverText ?: e.message ?: "") +
                (chosen?.preset?.appPasswordUrl?.let { " Most providers now need an app password for mail apps." } ?: "")
        } catch (e: CertificateException) {
            error = e.message
        } catch (e: Exception) {
            error = e.message ?: "Couldn't connect"
        } finally {
            if (busy != "Fetching your mail") {
                busy = null
                // A sign-in that didn't work leaves nothing sealed behind (re-auth keeps its own key).
                if (existing == null) runCatching { g.secrets.delete(account.secret) }
            }
        }
    }

    fun signIn() = scope.launch {
        val account = buildAccount()
        when {
            auth == AuthKind.OAUTH2 && account.oauthProvider == "google" -> {
                busy = "Waiting for Google"
                try {
                    g.tokens.accessToken(account)
                } catch (e: ConsentRequiredException) {
                    val c = e.consent as ConsentNeeded.Google
                    val result = bridge.startIntentSender(c.pendingIntent)
                    if (g.tokens.completeGoogleConsent(account.email, result.data) == null) { busy = null; error = "Google sign-in was cancelled"; return@launch }
                } catch (e: AuthenticationException) {
                    busy = null; error = e.message; return@launch
                }
                finish(account)
            }
            auth == AuthKind.OAUTH2 && account.oauthProvider == "microsoft" -> {
                busy = "Waiting for Microsoft"
                val result = bridge.startForResult(g.tokens.microsoftSignInIntent(bridge.activity, account.email))
                val data = result.data
                if (data == null) { busy = null; error = "Microsoft sign-in was cancelled"; return@launch }
                try {
                    g.tokens.completeMicrosoft(data, account.secret)
                } catch (e: AuthenticationException) { busy = null; error = e.message; return@launch }
                finish(account)
            }
            else -> {
                g.secrets.put(account.secret, secret.trim().replace(" ", "").takeIf { auth == AuthKind.APP_PASSWORD && it.length == 16 } ?: secret)
                finish(account)
            }
        }
    }

    Page(if (reauthAccountId != null) "Sign in again" else "Add an account", onDone) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            if (chosen == null && !manual) {
                LineField(email, { email = it; error = null }, "you@example.com", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                LineField(name, { name = it }, "Your name, as people see it")
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SolidButton(busy ?: "Continue", {
                        if (!email.contains('@') || busy != null) return@SolidButton
                        scope.launch {
                            busy = "Looking up ${email.substringAfter('@')}"
                            configs = runCatching { g.discovery.discover(email.trim()) }.getOrDefault(emptyList())
                            busy = null
                            val first = configs.firstOrNull()
                            if (first == null) { manual = true; error = "Couldn't find settings for ${email.substringAfter('@')} — enter them below." } else choose(first)
                        }
                    }, enabled = email.contains('@'))
                    TextAction("Enter servers myself", Palette.Navy, { manual = true })
                }
                SectionLabel("Works with")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in Providers.featured) SheetChip(p.name, false, { if (!email.contains('@')) email = "@" + (p.domains.firstOrNull() ?: "") })
                }
                Text("…and any provider with IMAP, POP3 or JMAP: Zoho, GMX, Posteo, mailbox.org, Yandex, your company's server.", style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 10.dp))
            } else {
                val preset = chosen?.preset
                Text(email, style = Type.heading)
                Text(preset?.name ?: chosen?.displayName ?: "Custom server", style = Type.body.copy(color = Palette.SheetInkDim))
                chosen?.source?.let { s -> if (s != DiscoverySource.PRESET) Text("Settings found via ${s.name.lowercase().replace('_', ' ')}", style = Type.small.copy(color = Palette.SheetInkDim)) }

                if (preset != null && !preset.supported) {
                    Text(preset.unsupportedReason ?: "${preset.name} doesn't allow other mail apps.", style = Type.body.copy(color = Palette.Danger), modifier = Modifier.padding(top = 16.dp))
                } else {
                    val options = (chosen?.auth ?: listOf(AuthKind.PASSWORD, AuthKind.APP_PASSWORD)).distinct()
                    if (options.size > 1) {
                        SectionLabel("Sign in with")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (o in options) SheetChip(
                                when (o) { AuthKind.OAUTH2 -> "${preset?.name ?: "Provider"} account"; AuthKind.APP_PASSWORD -> "App password"; AuthKind.API_TOKEN -> "API token"; AuthKind.PASSWORD -> "Password" },
                                auth == o, { auth = o },
                            )
                        }
                    }
                    if (auth == AuthKind.OAUTH2 && !oauthReady(chosen?.oauthProvider, g)) {
                        Text("Sign-in with ${preset?.name} isn't set up in this build (see README → Sign-in setup). Use an app password instead.", style = Type.small.copy(color = Palette.Warning), modifier = Modifier.padding(top = 8.dp))
                    }
                    if (auth != AuthKind.OAUTH2) {
                        if (auth == AuthKind.APP_PASSWORD || auth == AuthKind.API_TOKEN) {
                            for (step in preset?.setupSteps.orEmpty()) Text(step, style = Type.small, modifier = Modifier.padding(top = 8.dp))
                            preset?.appPasswordUrl?.let { url ->
                                TextAction(if (auth == AuthKind.API_TOKEN) "Create a token" else "Create an app password", Palette.Navy, { bridge.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                            }
                        }
                        LineField(secret, { secret = it; error = null }, when (auth) { AuthKind.APP_PASSWORD -> "App password"; AuthKind.API_TOKEN -> "API token"; else -> "Password" },
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                }
                TextAction(if (manual) "Hide server settings" else "Server settings", Palette.Navy, { manual = !manual }, Modifier.padding(top = 8.dp))
            }

            if (manual) {
                if (chosen == null) {
                    LineField(email, { email = it }, "you@example.com", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    LineField(name, { name = it }, "Your name")
                    LineField(secret, { secret = it }, "Password", visualTransformation = PasswordVisualTransformation())
                }
                SectionLabel("Incoming")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in IncomingProtocol.entries) SheetChip(p.name, protocol == p, {
                        protocol = p
                        if (p == IncomingProtocol.POP3 && inPort == "993") inPort = "995"
                        if (p == IncomingProtocol.IMAP && inPort == "995") inPort = "993"
                    })
                }
                if (protocol == IncomingProtocol.JMAP) {
                    LineField(jmapUrl, { jmapUrl = it }, "https://jmap.example.com/.well-known/jmap")
                } else {
                    LineField(inHost, { inHost = it }, "imap.example.com")
                    Row { LineField(inPort, { inPort = it.filter(Char::isDigit) }, "Port", Modifier.width(90.dp)); Spacer(Modifier.width(12.dp)); SecurityChips(inSec) { inSec = it } }
                    LineField(user, { user = it }, "Username (usually your address)")
                }
                SectionLabel("Sending (SMTP)")
                LineField(outHost, { outHost = it }, if (protocol == IncomingProtocol.JMAP) "Not needed for JMAP" else "smtp.example.com")
                Row { LineField(outPort, { outPort = it.filter(Char::isDigit) }, "Port", Modifier.width(90.dp)); Spacer(Modifier.width(12.dp)); SecurityChips(outSec) { outSec = it } }
                if (inSec == Security.NONE || outSec == Security.NONE) Text("Without encryption your password crosses the network readable. Only for a bridge on this device or your own network.", style = Type.small.copy(color = Palette.Danger), modifier = Modifier.padding(top = 6.dp))
            }

            error?.let { Text(it, style = Type.small.copy(color = Palette.Danger), modifier = Modifier.padding(top = 14.dp)) }
            if (chosen != null || manual) {
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SolidButton(busy ?: if (auth == AuthKind.OAUTH2) "Sign in with ${chosen?.preset?.name ?: "provider"}" else "Sign in", { if (busy == null) signIn() },
                        enabled = busy == null && email.contains('@') && (auth == AuthKind.OAUTH2 || secret.isNotBlank()) && (protocol == IncomingProtocol.JMAP && jmapUrl.isNotBlank() || inHost.isNotBlank()))
                    if (busy != null) ProvenanceMark(true, working = true, modifier = Modifier.padding(top = 14.dp))
                }
                if (configs.size > 1) {
                    SectionLabel("Other settings found")
                    for (c in configs.filter { it != chosen }.take(3)) TextAction("${c.displayName} · ${c.protocol}", Palette.Navy, { choose(c) })
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun oauthReady(provider: String?, g: dev.aarso.mail.app.AppGraph): Boolean = when (provider) {
    "google" -> true // Google's on-device Authorization API; errors explain registration if missing.
    "microsoft" -> g.tokens.microsoftConfigured
    else -> false
}

@Composable
private fun SecurityChips(value: Security, onChange: (Security) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        for (s in Security.entries) SheetChip(when (s) { Security.SSL_TLS -> "TLS"; Security.STARTTLS -> "STARTTLS"; Security.NONE -> "None" }, value == s, { onChange(s) })
    }
}
