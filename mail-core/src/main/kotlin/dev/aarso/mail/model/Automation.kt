package dev.aarso.mail.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A rule: a search query plus what to do with every message it matches.
 *
 * The condition is ordinary search syntax — the same grammar as the search box, the selection
 * rules and the saved views — so a person can type a query, see exactly which messages it finds,
 * and turn it into a rule without learning a second language. "Test" on a rule is literally running
 * its query.
 */
@Serializable
data class MailRule(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** search-core syntax, e.g. `from:@github.com subject:"[PR]" -is:starred`. */
    val query: String,
    /** Empty = every account. */
    val accountIds: Set<Long> = emptySet(),
    val triggers: Set<RuleTrigger> = setOf(RuleTrigger.NEW_MAIL),
    val actions: List<RuleAction>,
    /** Later rules do not see a message this rule matched. */
    val stopProcessing: Boolean = false,
    /** For [RuleTrigger.SCHEDULED]: minutes between runs (1440 = daily). */
    val intervalMinutes: Int = 1440,
    val lastRunAt: Long = 0,
    val matchCount: Long = 0,
    val createdAt: Long = 0,
)

@Serializable
enum class RuleTrigger { NEW_MAIL, MANUAL, SCHEDULED }

@Serializable
sealed interface RuleAction {
    @Serializable @SerialName("mark_read") data object MarkRead : RuleAction
    @Serializable @SerialName("mark_unread") data object MarkUnread : RuleAction
    @Serializable @SerialName("star") data object Star : RuleAction
    @Serializable @SerialName("unstar") data object Unstar : RuleAction
    @Serializable @SerialName("archive") data object Archive : RuleAction
    @Serializable @SerialName("trash") data object Trash : RuleAction
    @Serializable @SerialName("spam") data object Spam : RuleAction
    @Serializable @SerialName("pin") data object Pin : RuleAction
    @Serializable @SerialName("mute") data object Mute : RuleAction

    /** Move to a folder by its server name, or to a role ("ARCHIVE") when [role] is set. */
    @Serializable @SerialName("move") data class MoveTo(val mailbox: String? = null, val role: MailboxRole? = null) : RuleAction
    @Serializable @SerialName("label") data class AddLabel(val label: String) : RuleAction
    @Serializable @SerialName("unlabel") data class RemoveLabel(val label: String) : RuleAction
    @Serializable @SerialName("categorize") data class Categorize(val category: Category) : RuleAction
    @Serializable @SerialName("snooze") data class Snooze(val minutes: Int) : RuleAction
    @Serializable @SerialName("notify") data class Notify(val level: NotifyLevel) : RuleAction
    @Serializable @SerialName("forward") data class Forward(val to: String) : RuleAction
    @Serializable @SerialName("auto_reply") data class AutoReply(val subjectPrefix: String = "Re: ", val body: String) : RuleAction

    /** Summarise with the on-device model first (tl;dr shown in the list). */
    @Serializable @SerialName("summarize") data object Summarize : RuleAction
}

@Serializable
enum class NotifyLevel { URGENT, NORMAL, SILENT, NEVER }

/** A saved query: a smart folder in Places, a digest tab, or a reusable selection rule. */
@Serializable
data class SavedSearch(
    val id: Long = 0,
    val name: String,
    val query: String,
    val kind: SavedSearchKind = SavedSearchKind.VIEW,
    /** Shown as a tab beside REPLIES / TICKETS / OTP on the digest. */
    val showAsTab: Boolean = false,
    val pinned: Boolean = true,
    val sortOrder: Int = 0,
)

@Serializable
enum class SavedSearchKind { VIEW, SELECTION }

/**
 * Where one message lives on the server, captured when an op is queued. The local row is changed
 * optimistically the same instant (a moved message shows in its new folder at once), so the op must
 * remember the coordinates the *server* still has.
 */
@Serializable
data class OpTarget(val messageId: Long, val mailboxId: Long, val uid: Long, val remoteId: String? = null)

/** One queued server mutation. Applied locally at once, flushed to the server by the op queue. */
@Serializable
sealed interface PendingOp {
    val targets: List<OpTarget>

    @Serializable @SerialName("flags")
    data class SetFlags(override val targets: List<OpTarget>, val add: Int, val remove: Int) : PendingOp

    /** Gmail labels / JMAP mailbox membership. System labels (`\\Inbox`) archive and un-archive. */
    @Serializable @SerialName("labels")
    data class SetLabels(override val targets: List<OpTarget>, val add: List<String>, val remove: List<String>) : PendingOp

    @Serializable @SerialName("move")
    data class Move(override val targets: List<OpTarget>, val toMailboxId: Long) : PendingOp

    /** Permanent removal (from Trash, or a server without a Trash folder). */
    @Serializable @SerialName("expunge")
    data class Expunge(override val targets: List<OpTarget>) : PendingOp
}
