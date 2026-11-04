package dev.aarso.mail.app

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.aarso.mail.ai.CloudEngineConfig
import dev.aarso.mail.ai.CloudEngineFactory
import dev.aarso.mail.ai.Intelligence
import dev.aarso.mail.ai.LlmEngine
import dev.aarso.mail.ai.MailAi
import dev.aarso.mail.ai.TldrUpgrader
import dev.aarso.mail.ai.UsageLedger
import dev.aarso.mail.app.ai.GeminiNanoEngine
import dev.aarso.mail.app.ai.LocalModelEngine
import dev.aarso.mail.app.ai.OnDeviceTranslator
import dev.aarso.mail.app.platform.AndroidTokenProvider
import dev.aarso.mail.app.platform.Files
import dev.aarso.mail.app.platform.KeystoreSecrets
import dev.aarso.mail.app.platform.Notifier
import dev.aarso.mail.app.platform.PushService
import dev.aarso.mail.app.platform.Speech
import dev.aarso.mail.app.platform.Work
import dev.aarso.mail.digest.DigestBuilder
import dev.aarso.mail.discovery.AutoDiscovery
import dev.aarso.mail.intel.HeuristicAnalyzer
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Draft
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.net.TransportFactory
import dev.aarso.mail.rules.RuleEffects
import dev.aarso.mail.rules.RuleEngine
import dev.aarso.mail.search.MailSearch
import dev.aarso.mail.select.SelectionEngine
import dev.aarso.mail.store.MailStore
import dev.aarso.mail.sync.AccountConnector
import dev.aarso.mail.sync.MailActions
import dev.aarso.mail.sync.MailSync
import dev.aarso.mail.sync.Outbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Every long-lived object the app has, built once per process and shared by the UI, the workers,
 * the push service and the notification actions — so an archive from the shade and an archive
 * from the list are the same call with the same undo and the same queue.
 */
class AppGraph(val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    val store = MailStore(BundledSQLiteDriver(), context.getDatabasePath("mail.db").apply { parentFile?.mkdirs() }.path)
    val secrets = KeystoreSecrets(context)
    val tokens = AndroidTokenProvider(context, secrets)
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val connector = AccountConnector(secrets, tokens, TransportFactory.DEFAULT, http)
    val files = Files(context)
    val notifier = Notifier(context)
    val discovery = AutoDiscovery(http)

    val sync = MailSync(store, connector, HeuristicAnalyzer())
    val actions = MailActions(store).apply { onQueued = { ids -> Work.flushSoon(context, ids) } }
    val outbox = Outbox(store, connector, actions, files)
    val search = MailSearch(store)
    val selection = SelectionEngine(search)
    val digest = DigestBuilder(search)

    // ---- settings ----

    private val _settings = MutableStateFlow(runBlocking(Dispatchers.IO) { loadSettings() })
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private suspend fun loadSettings(): AppSettings =
        store.setting(SETTINGS_KEY)?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val before = _settings.value
        val next = change(before)
        if (next == before) return
        _settings.value = next
        scope.launch { store.putSetting(SETTINGS_KEY, json.encodeToString(AppSettings.serializer(), next)) }
        if (next.pollMinutes != before.pollMinutes) Work.schedulePeriodic(context, next.pollMinutes)
        if (next.alwaysOnPush != before.alwaysOnPush) PushService.sync(context, next.alwaysOnPush)
        if (next.ai != before.ai || next.cloudEngines != before.cloudEngines || next.localModelPath != before.localModelPath) intelligence.invalidate()
    }

    val accounts: StateFlow<List<Account>> = store.observeAccounts().stateIn(scope, SharingStarted.Eagerly, runBlocking(Dispatchers.IO) { store.accounts() })

    fun accountsNow(): List<Account> = accounts.value

    // ---- intelligence ----

    val nano = GeminiNanoEngine(context)
    val localModel = LocalModelEngine(context) { settings.value.localModelPath }
    val translator = OnDeviceTranslator()

    /** API keys, unsealed once and held in memory while the process lives (never written in clear). */
    private val apiKeys = ConcurrentHashMap<String, String>()
    private val cloud = ConcurrentHashMap<String, Pair<CloudEngineConfig, LlmEngine>>()

    private fun cloudEngine(c: CloudEngineConfig): LlmEngine {
        val key = apiKeys[c.keyRef]
        val cached = cloud[c.id]
        if (cached != null && cached.first == c) return cached.second
        return CloudEngineFactory.create(c, key).also { cloud[c.id] = c to it }
    }

    fun engines(): List<LlmEngine> = listOf(nano, localModel) + settings.value.cloudEngines.filter { it.enabled }.map(::cloudEngine)

    suspend fun putApiKey(config: CloudEngineConfig, key: String) {
        secrets.put(SecretRef(config.keyRef), key)
        apiKeys[config.keyRef] = key
        cloud.remove(config.id)
        intelligence.invalidate()
    }

    suspend fun forgetApiKey(config: CloudEngineConfig) {
        secrets.delete(SecretRef(config.keyRef))
        apiKeys.remove(config.keyRef)
        cloud.remove(config.id)
    }

    private val ledger = UsageLedger(
        initial = runBlocking(Dispatchers.IO) {
            store.setting(USAGE_KEY)?.let { runCatching { json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), it) }.getOrNull() }
        }.orEmpty(),
        persist = { m -> scope.launch { store.putSetting(USAGE_KEY, json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), m)) } },
    )
    fun cloudTokensToday(): Long = ledger.used()

    val intelligence = Intelligence(::engines, { settings.value.ai }, ledger)
    val ai = MailAi(intelligence)
    val tldrs = TldrUpgrader(store, ai) { settings.value.ai }
    /** The id of the line being read aloud (a message id, or "intro"), for the list to light up. */
    val speaking = MutableStateFlow<String?>(null)
    val speech: Speech by lazy { Speech(context, speaking) }

    // ---- rules ----

    private val effects = object : RuleEffects {
        override suspend fun forward(message: MessageHeader, to: String) {
            val account = store.account(message.accountId) ?: return
            val d = Drafts.forward(account, message, sync.fetchBody(message.id), emptyList())
            send(d.copy(to = listOf(dev.aarso.mail.model.EmailAddress(to))), undoSeconds = 0)
        }

        override suspend fun autoReply(message: MessageHeader, subjectPrefix: String, body: String) {
            // Never answer machines, lists, or myself — that is how mail loops start.
            if (message.isBulk || message.isAutomated) return
            val account = store.account(message.accountId) ?: return
            if (message.from?.normalized in account.myAddresses) return
            val d = Drafts.reply(account, message, null, all = false)
            send(d.copy(subject = subjectPrefix.ifBlank { "Re:" } + " " + Drafts.baseSubject(message.subject), textBody = body), undoSeconds = 0)
        }

        override suspend fun summarize(messages: List<MessageHeader>) {
            tldrs.upgrade(messages.map { it.id }, limit = messages.size)
        }
    }
    val rules = RuleEngine(store, search, actions, effects)

    // ---- lifecycle ----

    init {
        sync.onNewMail = { account, fresh -> onNewMail(account, fresh) }
    }

    fun start() {
        scope.launch {
            for (c in settings.value.cloudEngines) secrets.get(SecretRef(c.keyRef))?.let { apiKeys[c.keyRef] = it }
            search.refreshAccounts()
            accounts.collect { search.refreshAccounts() }
        }
        scope.launch {
            // First run of a new process: anything due while we were dead goes out / comes back now.
            runReminders()
            scheduleOutbox()
        }
    }

    private suspend fun onNewMail(account: Account, fresh: List<MessageHeader>) {
        val outcome = runCatching { rules.onNewMail(account, fresh) }.getOrNull()
        // Rules may have archived or read some of them: notify about what is still in the inbox.
        val ids = fresh.map { it.id }.toSet()
        val stillInbox = search.matchesAmong(search.parse("in:inbox", natural = false), ids)
        val current = store.headers(stillInbox)
        notifier.notifyNew(account, current, outcome, settings.value)
        scope.launch {
            runCatching { tldrs.upgrade(current.sortedByDescending { it.receivedAt }.map { it.id }, limit = 24) }
            runCatching { tldrs.refineCategories(current.map { it.id }) }
        }
    }

    /** After any background sync: due reminders, scheduled rules, and the next wake-ups. */
    suspend fun afterSync() {
        runReminders()
        runCatching { rules.runScheduled() }
        scheduleOutbox()
    }

    suspend fun runReminders() {
        val now = System.currentTimeMillis()
        for (r in store.dueReminders(now)) {
            val h = store.header(r.messageId)
            when (r.kind) {
                "snooze" -> if (h != null && h.snoozedUntil in 1..now) {
                    store.setSnooze(listOf(h.id), 0)
                    actions.markRead(listOf(h.id), read = false)
                    store.header(h.id)?.let { notifier.notifyReminder(it, "snooze") }
                }
                "followup" -> if (h != null) {
                    val thread = store.threadMessages(h.threadKey, h.accountId)
                    val account = store.account(h.accountId)
                    val sentId = r.note
                    val replied = thread.any { m ->
                        m.from?.normalized !in account?.myAddresses.orEmpty() &&
                            (m.inReplyTo == sentId || (sentId != null && sentId in m.references))
                    }
                    if (!replied) notifier.notifyReminder(h, "followup")
                }
            }
            store.completeReminder(r.id)
        }
        store.nextReminderAt()?.let { Work.remindAt(context, it) }
    }

    suspend fun scheduleOutbox() {
        store.outbox().filter { it.state == "QUEUED" }.minOfOrNull { it.sendAt }?.let { Work.sendAt(context, it) }
    }

    /** Queue a message; it leaves after the undo window (or at its scheduled time). Returns the outbox id. */
    suspend fun send(draft: Draft, undoSeconds: Int = settings.value.undoSendSeconds): Long {
        val id = outbox.queue(draft, undoSeconds)
        if (draft.id > 0) store.deleteDraft(draft.id)
        scheduleOutbox()
        return id
    }

    suspend fun quickReply(messageId: Long, text: String) {
        val h = store.header(messageId) ?: return
        val account = store.account(h.accountId) ?: return
        val d = Drafts.reply(account, h, store.body(messageId), all = false)
        send(d.copy(textBody = text + d.textBody), undoSeconds = 0)
        actions.markRead(listOf(messageId))
    }

    companion object {
        const val SETTINGS_KEY = "app.settings"
        const val USAGE_KEY = "ai.usage"
    }
}
