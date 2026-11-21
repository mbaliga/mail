package dev.aarso.mail.app.ui

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEvent
import dev.aarso.mail.app.AppGraph
import dev.aarso.mail.model.Draft
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.sync.UndoToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The pages that open over home. Home itself (and its four rooms) is always underneath. */
sealed interface Screen {
    /** A list: a folder, a label, a saved view, a search. [query] is in the shared grammar. */
    data class Mailbox(val title: String, val query: String, val accountIds: Set<Long> = emptySet(), val mailboxIds: Set<Long> = emptySet()) : Screen
    data class Reader(val messageId: Long, val siblings: List<Long> = emptyList()) : Screen
    data class Compose(val draft: Draft) : Screen
    data object Settings : Screen
    data class AccountSetup(val reauthAccountId: Long? = null) : Screen
    data class RuleEditor(val rule: MailRule?) : Screen
    data object Outbox : Screen
    /** A message opened from a file (.eml) rather than from an account. */
    data class External(val raw: ByteArray, val name: String) : Screen {
        override fun equals(other: Any?) = other is External && other.name == name && other.raw.contentEquals(raw)
        override fun hashCode() = name.hashCode() * 31 + raw.contentHashCode()
    }
    data object Shortcuts : Screen
}

/** What the activity can do for Compose code: results, pickers, consent screens. */
interface ActivityBridge {
    val activity: android.app.Activity
    suspend fun startForResult(intent: Intent): ActivityResult
    suspend fun startIntentSender(pending: PendingIntent): ActivityResult
    suspend fun pickDocuments(types: Array<String>, multiple: Boolean): List<Uri>
    suspend fun createDocument(name: String, mime: String): Uri?
    fun launch(intent: Intent)
    /** Ask for POST_NOTIFICATIONS (Android 13+) — once there is mail to notify about. */
    fun requestNotifications()
    /** Ask for a runtime permission; true if granted. */
    suspend fun requestPermission(permission: String): Boolean
}

val LocalBridge = staticCompositionLocalOf<ActivityBridge> { error("No ActivityBridge") }
val LocalApp = staticCompositionLocalOf<AppState> { error("No AppState") }

/**
 * One open thing: Home ("Inbox"), a message or conversation, a draft, the outbox. Each tab keeps its
 * own back stack, so a list opened from Home and a draft written beside it never trample each other.
 */
@Stable
class Tab(val id: Long, title: String, val closable: Boolean = true) {
    var title by mutableStateOf(title)
    val stack = mutableStateListOf<Screen>()
    /** What the tab is about, for finding it again instead of opening a second copy. */
    val root: Screen? get() = stack.firstOrNull()
}

@Stable
class AppState(val graph: AppGraph, val scope: CoroutineScope) {
    val home = Tab(0, "Inbox", closable = false)
    val tabs = mutableStateListOf(home)
    var active by mutableStateOf(home)
        private set
    private val recent = ArrayDeque<Tab>()
    private var nextTab = 1L

    /** The visible tab's pages. Everything that used to push onto "the stack" still does. */
    val stack: androidx.compose.runtime.snapshots.SnapshotStateList<Screen> get() = active.stack

    fun activate(tab: Tab) {
        if (tab === active) return
        recent.remove(active); recent.addLast(active)
        active = tab
        recent.remove(tab)
    }

    fun closeTab(tab: Tab) {
        if (!tab.closable) return
        val wasActive = tab === active
        val at = tabs.indexOf(tab)
        tabs.remove(tab)
        recent.remove(tab)
        if (wasActive) active = recent.removeLastOrNull()?.takeIf { it in tabs } ?: tabs.getOrElse((at - 1).coerceAtLeast(0)) { home }
    }

    private fun tabFor(screen: Screen, title: String, matches: (Screen) -> Boolean): Tab {
        tabs.firstOrNull { t -> t.root?.let(matches) == true }?.let { activate(it); return it }
        val t = Tab(nextTab++, title)
        t.stack.add(screen)
        tabs.add(t)
        activate(t)
        return t
    }

    fun open(screen: Screen) {
        when (screen) {
            // Things you read, write or wait on get a tab of their own and stay open while you go elsewhere.
            is Screen.Reader -> {
                val before = tabs.size
                val t = tabFor(screen, "…") { it is Screen.Reader && it.messageId == screen.messageId }
                if (tabs.size > before) scope.launch { graph.store.header(screen.messageId)?.let { t.title = it.subject.ifBlank { "(no subject)" } } }
            }
            is Screen.Compose -> {
                val d = screen.draft
                tabFor(screen, d.subject.ifBlank { "New mail" }) { it is Screen.Compose && d.id != 0L && it.draft.id == d.id }
            }
            Screen.Outbox -> tabFor(screen, "Outbox") { it == Screen.Outbox }
            is Screen.External -> tabFor(screen, screen.name) { it == screen }
            // Navigation (lists, settings, setup) belongs to Home.
            else -> {
                activate(home)
                if (home.stack.lastOrNull() != screen) home.stack.add(screen)
            }
        }
    }

    fun replace(screen: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(screen)
    }

    /** Back: out of the page; out of the tab when it was the last page; false when already Home with nothing open. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        if (stack.isEmpty() && active.closable) closeTab(active)
        return true
    }

    /** Back to bare Home: closes pages on Home, leaves other tabs open. */
    fun home() { activate(home); home.stack.clear() }

    fun compose(draft: Draft) = open(Screen.Compose(draft))

    // ---- undo, for every reversible action in the app ----

    private val _undo = MutableStateFlow<UndoToken?>(null)
    val undo: StateFlow<UndoToken?> = _undo.asStateFlow()

    /** Run a mail action and offer its undo. */
    fun act(block: suspend () -> UndoToken) {
        scope.launch {
            val t = block()
            if (t.count > 0) _undo.value = t
        }
    }

    /** Offer undo for a batch that was applied outside [act]. */
    fun offerUndo(t: UndoToken) { _undo.value = t }

    fun undoLast() {
        val t = _undo.value ?: return
        _undo.value = null
        scope.launch { graph.actions.undo(t) }
    }

    fun dismissUndo() { _undo.value = null }

    // ---- the outbox's undo-send window ----

    data class Sending(val outboxId: Long, val subject: String, val until: Long)

    private val _sending = MutableStateFlow<Sending?>(null)
    val sending: StateFlow<Sending?> = _sending.asStateFlow()

    fun sent(outboxId: Long, subject: String, seconds: Int) {
        if (seconds > 0) _sending.value = Sending(outboxId, subject, System.currentTimeMillis() + seconds * 1000L)
    }

    fun recall() {
        val s = _sending.value ?: return
        _sending.value = null
        scope.launch { graph.outbox.recall(s.outboxId)?.let { d -> compose(graph.store.saveDraft(d)) } }
    }

    fun clearSending() { _sending.value = null }

    // ---- short notes ("Copied", "Saved to Downloads") ----

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notes: SharedFlow<String> = _notes.asSharedFlow()
    fun say(text: String) { _notes.tryEmit(text) }

    // ---- keyboard: the top-most screen that registered a handler gets first refusal ----

    val keyHandlers = mutableStateListOf<(KeyEvent) -> Boolean>()
}
