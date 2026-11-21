package dev.aarso.mail.app.ai

import dev.aarso.mail.ai.ActionKind
import dev.aarso.mail.ai.AgentEvent
import dev.aarso.mail.ai.AgentHost
import dev.aarso.mail.ai.AgentRun
import dev.aarso.mail.ai.MailAgent
import dev.aarso.mail.ai.ProposedAction
import dev.aarso.mail.app.AppGraph
import dev.aarso.mail.app.Drafts
import dev.aarso.mail.app.ui.AppState
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.RuleTrigger
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.search.SortOrder
import dev.aarso.mail.sync.UndoToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** The agent's eyes: the person's own store and search, nothing else. */
class StoreAgentHost(private val g: AppGraph) : AgentHost {
    override suspend fun search(query: String, limit: Int): List<MessageHeader> =
        g.search.search(g.search.parse(query, natural = false), SearchScope(), SortOrder.NEWEST, limit, countTotal = false).headers

    override suspend fun count(query: String): Int = g.search.count(g.search.parse(query, natural = false), SearchScope())

    override suspend fun body(id: Long): MessageBody? =
        g.store.body(id)?.takeIf { it.text != null || it.html != null } ?: runCatching { g.sync.fetchBody(id) }.getOrNull()

    override fun todayIso(): String = java.time.LocalDate.now().toString()
}

/**
 * The home assistant: runs the agent, keeps what it found for the home screen to offer, and applies
 * whatever the person accepts. Nothing it suggests happens until then; each batch can be undone.
 */
class Assistant(private val g: AppGraph) {

    sealed interface State {
        data object Idle : State
        data class Running(val goal: String, val engine: String?, val onDevice: Boolean, val trace: List<String>, val background: Boolean) : State
        data class Done(val goal: String, val run: AgentRun) : State
        data class Failed(val goal: String, val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var lastTriageAt = 0L

    private val agent = MailAgent(g.intelligence, StoreAgentHost(g))

    fun start(goal: String, allowCloud: Boolean = true, background: Boolean = false) {
        if (_state.value is State.Running) return
        job = g.scope.launch {
            _state.value = State.Running(goal, null, true, emptyList(), background)
            val trace = ArrayList<String>()
            var engine: String? = null
            var onDevice = true
            fun push() { _state.value = State.Running(goal, engine, onDevice, trace.toList(), background) }
            try {
                val run = agent.run(goal, allowCloud) { e ->
                    when (e) {
                        is AgentEvent.Engine -> { engine = e.label; onDevice = e.onDevice }
                        is AgentEvent.Searching -> trace += "Looked through “${e.query}” — ${e.found} found"
                        is AgentEvent.Counting -> trace += "Counted “${e.query}” — ${e.count}"
                        is AgentEvent.Reading -> trace += "Read “${e.subject.take(60)}”"
                        is AgentEvent.Proposed -> trace += "Suggests: ${e.action.title}"
                        is AgentEvent.Retry -> trace += "Asked the model to try again"
                    }
                    push()
                }
                _state.value = when {
                    run == null -> if (background) State.Idle else State.Failed(goal, NO_MODEL)
                    background && run.proposals.isEmpty() -> State.Idle
                    else -> State.Done(goal, run)
                }
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = if (background) State.Idle else State.Failed(goal, e.message ?: "Something went wrong")
            }
        }
    }

    /** Quietly, on-device only, at most every few hours: look through what's new and keep the suggestions. */
    fun triageIfDue(windowPhrase: String) {
        val now = System.currentTimeMillis()
        if (!g.settings.value.assistantAuto || now - lastTriageAt < TRIAGE_EVERY_MS || _state.value !is State.Idle) return
        lastTriageAt = now
        start(tidyGoal(windowPhrase), allowCloud = false, background = true)
    }

    /** For previews and tests. */
    internal fun setState(s: State) { _state.value = s }

    fun cancel() { job?.cancel(); _state.value = State.Idle }
    fun dismiss() { _state.value = State.Idle }

    /** Run what was accepted. Returns how many messages changed; drafts open in tabs, rules open for review. */
    suspend fun apply(actions: List<ProposedAction>, app: AppState): Int {
        var changed = 0
        var token: UndoToken? = null
        for (a in actions) {
            when (a.kind) {
                ActionKind.ARCHIVE -> token = g.actions.archive(a.ids).also { changed += it.count }
                ActionKind.TRASH -> token = g.actions.trash(a.ids).also { changed += it.count }
                ActionKind.MARK_READ -> token = g.actions.markRead(a.ids).also { changed += it.count }
                ActionKind.STAR -> token = g.actions.star(a.ids).also { changed += it.count }
                ActionKind.LABEL -> a.arg?.let { l -> token = g.actions.label(a.ids, listOf(l)).also { changed += it.count } }
                ActionKind.SNOOZE -> a.arg?.let { at -> parseTime(at)?.let { ms -> token = g.actions.snooze(a.ids, ms).also { changed += it.count } } }
                ActionKind.DRAFT_REPLY -> {
                    val h = a.ids.firstOrNull()?.let { g.store.header(it) }
                    val acct = h?.let { g.store.account(it.accountId) }
                    if (h != null && acct != null) {
                        val body = g.store.body(h.id)
                        val d = Drafts.reply(acct, h, body, all = false)
                        app.compose(d.copy(textBody = (a.arg.orEmpty().trim() + "\n\n") + d.textBody))
                    }
                }
                ActionKind.MAKE_RULE -> {
                    val act = when (a.arg) { "trash" -> RuleAction.Trash; "mark_read" -> RuleAction.MarkRead; "star" -> RuleAction.Star; else -> RuleAction.Archive }
                    app.open(Screen.RuleEditor(MailRule(name = a.title.take(60), query = a.query.orEmpty(), actions = listOf(act), triggers = setOf(RuleTrigger.NEW_MAIL))))
                }
            }
        }
        token?.takeIf { it.count > 0 }?.let { t -> app.offerUndo(t) }
        return changed
    }

    private fun parseTime(s: String): Long? = runCatching {
        val t = if (s.length <= 10) LocalDateTime.parse(s + "T09:00") else LocalDateTime.parse(s.take(16))
        t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    companion object {
        const val NO_MODEL = "No model could run this. Download an on-device model in Settings → Intelligence, or allow “the home assistant” for your own key."
        const val TRIAGE_EVERY_MS = 6 * 3_600_000L

        fun tidyGoal(window: String) =
            "Look through the mail that arrived $window and suggest a few useful things: archive or mark read what is clearly done with or low value (newsletters, receipts already read), " +
                "draft a short reply to anything that asks the owner a direct question, and snooze anything that needs attention later. Be selective."
    }
}
