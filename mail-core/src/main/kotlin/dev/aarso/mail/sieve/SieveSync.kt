package dev.aarso.mail.sieve

import dev.aarso.mail.jmap.JmapClient
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.RunLocation
import dev.aarso.mail.model.Security
import dev.aarso.mail.net.TransportFactory
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.sync.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Where a rule actually runs once the server's abilities are known; [serverActions] are the indexes (into the rule's actions) the server carries. */
@Serializable
data class Placement(val ruleId: Long, val location: RunLocation, val serverActions: List<Int> = emptyList(), val reasons: List<String> = emptyList())

/** How to reach a server's Sieve. */
sealed interface SieveEndpoint {
    data class Manage(val host: String, val port: Int) : SieveEndpoint
    data object Jmap : SieveEndpoint
    data class None(val why: String) : SieveEndpoint
}

/** Which accounts have a server that can run rules, and how to reach it. The honest default for an unknown server is "try ManageSieve on its IMAP host". */
object SieveSupport {
    private val NONE = mapOf(
        "gmail" to "Gmail runs its own filters, which need extra permissions this app doesn't have yet",
        "icloud" to "iCloud has no server-side rules a mail app can reach",
        "outlook" to "Outlook.com rules need extra Microsoft permissions this app doesn't have yet",
        "m365" to "Microsoft 365 rules need extra permissions this app doesn't have yet",
        "yahoo" to "Yahoo has no server-side rules a mail app can reach",
        "aol" to "AOL has no server-side rules a mail app can reach",
        "proton" to "Proton Mail encrypts mail, so it can't run rules for you",
        "zoho" to "Zoho has no server-side rules a mail app can reach",
        "gmx" to "GMX has no server-side rules a mail app can reach", "webde" to "WEB.DE has no server-side rules a mail app can reach",
        "mailcom" to "mail.com has no server-side rules a mail app can reach",
    )

    fun endpointFor(a: Account): SieveEndpoint {
        if (a.protocol == IncomingProtocol.JMAP) return SieveEndpoint.Jmap
        NONE[a.providerId]?.let { return SieveEndpoint.None(it) }
        if (a.protocol == IncomingProtocol.POP3) return SieveEndpoint.None("POP3 accounts have no server-side rules")
        if (a.auth == AuthKind.OAUTH2) return SieveEndpoint.None("This account signs in with a provider login, which Sieve servers don't accept")
        return when (a.providerId) {
            "mailboxorg" -> SieveEndpoint.Manage("imap.mailbox.org", 4190)
            "posteo" -> SieveEndpoint.Manage("posteo.de", 4190)
            else -> a.incoming?.host?.let { SieveEndpoint.Manage(it, 4190) } ?: SieveEndpoint.None("This account has no mail server address")
        }
    }
}

fun interface SieveCredentials { suspend fun of(account: Account): Pair<String, String>? }
fun interface JmapProvider { suspend fun of(account: Account): JmapClient? }

/**
 * Keeps the server's Sieve script in step with the rules, and remembers which rules the server now carries so the phone never runs
 * one twice.
 *
 * Per account: the rules that may run on the server ([RunLocation] AUTO, SERVER or BOTH) are compiled against what the server says
 * it can do; those that compile become one script, uploaded under one name and activated; the rest — and the reason — stay on the
 * phone. A *foreign* active script (one the person made elsewhere) is never replaced unless they say so: Sieve allows only one active
 * script, so activating ours would switch theirs off.
 */
class SieveSync(
    private val store: MailStore,
    private val compiler: SieveCompiler,
    private val credentials: SieveCredentials,
    private val jmap: JmapProvider = JmapProvider { null },
    private val transports: TransportFactory = TransportFactory.DEFAULT,
    private val clock: Clock = Clock.SYSTEM,
) {
    sealed interface Outcome {
        data class Synced(val placements: List<Placement>, val uploaded: Boolean) : Outcome
        data class Unsupported(val why: String) : Outcome
        /** The server already runs a script this app did not write; nothing was changed. */
        data class ForeignScript(val name: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    @Serializable private class State(val hash: String, val placements: List<Placement>, val at: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private fun key(accountId: Long) = "sieve.$accountId"

    /** Where each of this account's rules runs now (only rules that were placed; everything else runs on the phone). */
    suspend fun placements(accountId: Long): Map<Long, Placement> =
        store.setting(key(accountId))?.let { runCatching { json.decodeFromString(State.serializer(), it).placements }.getOrNull() }.orEmpty().associateBy { it.ruleId }

    private suspend fun remember(accountId: Long, hash: String, placements: List<Placement>) =
        store.putSetting(key(accountId), json.encodeToString(State.serializer(), State(hash, placements, clock.now())))

    suspend fun forget(accountId: Long) = store.putSetting(key(accountId), null)

    private fun folderNames(account: Account, boxes: List<dev.aarso.mail.model.Mailbox>): (MailboxRole?, String?) -> String? = { role, name ->
        when {
            role != null -> boxes.firstOrNull { it.role == role }?.remoteId
            name != null -> boxes.firstOrNull { it.remoteId == name || it.name.equals(name, true) }?.remoteId
            else -> null
        }
    }

    /** The cheap pre-check: could any of these rules compile if the server were as capable as can be? Saves connecting for nothing. */
    fun anyMayRunOnServer(rules: List<MailRule>, account: Account): Boolean {
        val everything = ServerCapabilities(ServerCapabilities.Transport.MANAGESIEVE, setOf("fileinto", "imap4flags", "copy"), account.myAddresses) { r, n -> n ?: r?.name }
        return rules.any { r ->
            r.enabled && r.runLocation != RunLocation.DEVICE && (r.accountIds.isEmpty() || account.id in r.accountIds) &&
                (compiler.compile(r, everything) is Compiled || r.runLocation == RunLocation.BOTH && compiler.compileActionsOnly(r, everything) != null)
        }
    }

    /** Where a rule would run, and if not on a server, why not — for the editor, before anything is saved or any server is contacted. */
    data class PlacementPreview(val location: RunLocation, val reasons: List<String>, val account: Account? = null)

    fun previewPlacement(rule: MailRule, accounts: List<Account>): PlacementPreview {
        if (rule.runLocation == RunLocation.DEVICE) return PlacementPreview(RunLocation.DEVICE, listOf("you chose to keep it on this phone"))
        val mine = accounts.filter { it.enabled && (rule.accountIds.isEmpty() || it.id in rule.accountIds) }
        if (mine.isEmpty()) return PlacementPreview(RunLocation.DEVICE, listOf("there is no account for it yet"))
        // A reason about the rule itself ("it uses tabs") says more than one about an account that has no server rules at all.
        var firstReason: List<String>? = null; var accountReason: List<String>? = null
        for (a in mine) {
            when (val ep = SieveSupport.endpointFor(a)) {
                is SieveEndpoint.None -> accountReason = accountReason ?: listOf(ep.why)
                else -> {
                    val caps = ServerCapabilities(if (ep is SieveEndpoint.Jmap) ServerCapabilities.Transport.JMAP else ServerCapabilities.Transport.MANAGESIEVE,
                        setOf("fileinto", "imap4flags", "copy"), a.myAddresses) { r, n -> n ?: r?.name }
                    when (val c = compiler.compile(rule, caps)) {
                        is Compiled -> return PlacementPreview(RunLocation.SERVER, emptyList(), a)
                        is NotPortable -> firstReason = firstReason ?: c.reasons
                    }
                }
            }
        }
        return PlacementPreview(RunLocation.DEVICE, (firstReason ?: accountReason).orEmpty())
    }

    suspend fun sync(account: Account, allRules: List<MailRule>, allowTakeover: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        val rules = allRules.filter { it.enabled && (it.accountIds.isEmpty() || account.id in it.accountIds) }
        when (val ep = SieveSupport.endpointFor(account)) {
            is SieveEndpoint.None -> { forget(account.id); Outcome.Unsupported(ep.why) }
            is SieveEndpoint.Manage -> runCatching { viaManageSieve(account, rules, ep, allowTakeover) }.getOrElse { Outcome.Failed(it.message ?: "Couldn't reach the server's rules") }
            SieveEndpoint.Jmap -> runCatching { viaJmap(account, rules, allowTakeover) }.getOrElse { Outcome.Failed(it.message ?: "Couldn't reach the server's rules") }
        }
    }

    // ---- planning, shared by both ways in ----

    private class Plan(val script: String, val placements: List<Placement>)

    private suspend fun plan(account: Account, rules: List<MailRule>, caps: ServerCapabilities): Plan {
        val placements = ArrayList<Placement>(); val onServer = ArrayList<MailRule>()
        for (r in rules.sortedWith(compareBy({ it.order }, { it.id }))) {
            when {
                r.runLocation == RunLocation.DEVICE -> placements += Placement(r.id, RunLocation.DEVICE, reasons = listOf("you chose to run it on this phone"))
                r.runLocation == RunLocation.BOTH -> {
                    val part = compiler.compileActionsOnly(r, caps)
                    if (part == null) placements += Placement(r.id, RunLocation.DEVICE, reasons = (compiler.compile(r, caps) as? NotPortable)?.reasons.orEmpty())
                    else {
                        val allPortable = part.second.size == r.actions.size
                        placements += Placement(r.id, if (allPortable) RunLocation.SERVER else RunLocation.BOTH, part.second)
                        onServer += r.copy(actions = part.second.map { r.actions[it] })
                    }
                }
                else -> when (val res = compiler.compile(r, caps)) {
                    is Compiled -> { placements += Placement(r.id, RunLocation.SERVER, r.actions.indices.toList()); onServer += r }
                    is NotPortable -> placements += Placement(r.id, RunLocation.DEVICE, reasons = res.reasons)
                }
            }
        }
        return Plan(compiler.script(onServer, caps).text, placements)
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- ManageSieve ----

    private suspend fun viaManageSieve(account: Account, rules: List<MailRule>, ep: SieveEndpoint.Manage, allowTakeover: Boolean): Outcome {
        if (!anyMayRunOnServer(rules, account) && placements(account.id).isEmpty()) return Outcome.Synced(rules.map { Placement(it.id, RunLocation.DEVICE) }, false)
        val (user, secret) = credentials.of(account) ?: return Outcome.Unsupported("This account's sign-in can't be used for server rules")
        val c = ManageSieveClient.connect(transports, ep.host, ep.port, user, secret, Security.STARTTLS)
        c.use {
            val caps = ServerCapabilities(ServerCapabilities.Transport.MANAGESIEVE, c.capabilities.sieve, account.myAddresses, folderNames(account, store.mailboxes(account.id)))
            val plan = plan(account, rules, caps)
            val scripts = c.listScripts()
            val foreign = scripts.firstOrNull { it.active && it.name != SCRIPT_NAME }
            val anyServer = plan.placements.any { it.location != RunLocation.DEVICE }
            if (!anyServer) {
                // Nothing for the server to do: take our script away if we left one.
                if (scripts.any { it.name == SCRIPT_NAME }) { if (scripts.first { it.name == SCRIPT_NAME }.active) c.setActive(""); c.deleteScript(SCRIPT_NAME) }
                remember(account.id, "", plan.placements)
                return Outcome.Synced(plan.placements, false)
            }
            if (foreign != null && !allowTakeover) { forget(account.id); return Outcome.ForeignScript(foreign.name) }
            val current = scripts.firstOrNull { it.name == SCRIPT_NAME }
            val same = current != null && current.active && c.getScript(SCRIPT_NAME) == plan.script
            if (!same) { c.putScript(SCRIPT_NAME, plan.script); c.setActive(SCRIPT_NAME) }
            remember(account.id, hash(plan.script), plan.placements)
            return Outcome.Synced(plan.placements, !same)
        }
    }

    // ---- JMAP ----

    private suspend fun viaJmap(account: Account, rules: List<MailRule>, allowTakeover: Boolean): Outcome {
        val client = jmap.of(account) ?: return Outcome.Unsupported("This account's sign-in can't be used for server rules")
        if (!client.sieveSupported()) { forget(account.id); return Outcome.Unsupported("This server doesn't offer Sieve scripts over JMAP") }
        val ext = client.sieveExtensions().ifEmpty { setOf("fileinto", "imap4flags", "copy") }
        val caps = ServerCapabilities(ServerCapabilities.Transport.JMAP, ext, account.myAddresses, folderNames(account, store.mailboxes(account.id)))
        val plan = plan(account, rules, caps)
        val scripts = client.sieveScripts()
        val foreign = scripts.firstOrNull { it.isActive && it.name != SCRIPT_NAME }
        if (plan.placements.none { it.location != RunLocation.DEVICE }) {
            scripts.firstOrNull { it.name == SCRIPT_NAME && it.isActive }?.let { client.deactivateSieve() }
            remember(account.id, "", plan.placements)
            return Outcome.Synced(plan.placements, false)
        }
        if (foreign != null && !allowTakeover) { forget(account.id); return Outcome.ForeignScript(foreign.name ?: foreign.id) }
        val last = store.setting(key(account.id))?.let { runCatching { json.decodeFromString(State.serializer(), it).hash }.getOrNull() }
        val ours = scripts.firstOrNull { it.name == SCRIPT_NAME }
        val same = ours != null && ours.isActive && last == hash(plan.script)
        if (!same) client.putSieveScript(SCRIPT_NAME, plan.script, activate = true)
        remember(account.id, hash(plan.script), plan.placements)
        return Outcome.Synced(plan.placements, !same)
    }

    /**
     * Is our script still the one the server runs? If the person switched it off elsewhere, the phone must take its rules back —
     * otherwise they would run nowhere. Returns false (and forgets the placements) when it is gone.
     */
    suspend fun stillActive(account: Account): Boolean = withContext(Dispatchers.IO) {
        if (placements(account.id).none { it.value.location != RunLocation.DEVICE }) return@withContext true
        val ok = runCatching {
            when (val ep = SieveSupport.endpointFor(account)) {
                is SieveEndpoint.Manage -> {
                    val (user, secret) = credentials.of(account) ?: return@runCatching false
                    ManageSieveClient.connect(transports, ep.host, ep.port, user, secret).use { it.listScripts().any { s -> s.name == SCRIPT_NAME && s.active } }
                }
                SieveEndpoint.Jmap -> jmap.of(account)?.sieveScripts()?.any { it.name == SCRIPT_NAME && it.isActive } ?: false
                is SieveEndpoint.None -> false
            }
        }.getOrNull()
        if (ok == false) forget(account.id)
        ok != false
    }

    companion object {
        /** The one script name this app owns on a server. */
        const val SCRIPT_NAME = "youve-got-mail"
    }
}
