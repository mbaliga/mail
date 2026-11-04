package dev.aarso.mail.app.ui

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
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
}

val LocalBridge = staticCompositionLocalOf<ActivityBridge> { error("No ActivityBridge") }
val LocalApp = staticCompositionLocalOf<AppState> { error("No AppState") }

@Stable
class AppState(val graph: AppGraph, val scope: CoroutineScope) {
    val stack = mutableStateListOf<Screen>()

    fun open(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun replace(screen: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun home() = stack.clear()

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
