package dev.aarso.mail.model

import kotlinx.serialization.Serializable

/**
 * The mail domain, in the vocabulary every other package speaks.
 *
 * These are plain values. Nothing here knows about a wire protocol or a database — the IMAP,
 * JMAP and POP3 clients translate *into* these types, and the store persists them — so a message
 * that arrived over JMAP from Fastmail and one that arrived over IMAP from iCloud are the same
 * thing by the time any UI, rule, search or model sees them.
 */

/** An RFC 5322 mailbox: `"Ada Lovelace" <ada@example.org>`. [address] is never blank. */
@Serializable
data class EmailAddress(val address: String, val name: String? = null) {
    /** Lower-cased for comparison; addresses are case-insensitive in practice, if not in theory. */
    val normalized: String get() = address.trim().lowercase()
    val domain: String get() = normalized.substringAfterLast('@', "")
    val localPart: String get() = normalized.substringBeforeLast('@', normalized)

    /** What a person reads: the display name when there is a meaningful one, else the address. */
    fun displayName(): String = name?.takeIf { it.isNotBlank() && !it.equals(address, true) } ?: address

    /** A short form for dense UI: first word of the name, or the local part. */
    fun shortName(): String = name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?.trim('"', '\'') ?: localPart

    override fun toString(): String = if (name.isNullOrBlank()) address else "$name <$address>"
}

enum class IncomingProtocol { IMAP, JMAP, POP3 }

enum class Security { SSL_TLS, STARTTLS, NONE }

/** How an account proves who it is. The secret itself never lives on these types — see [SecretRef]. */
enum class AuthKind {
    /** A normal password (generic IMAP hosts). */
    PASSWORD,

    /** A provider-issued app-specific password: iCloud, Yahoo, AOL, Gmail with 2-Step Verification. */
    APP_PASSWORD,

    /** OAuth 2.0 access token over SASL XOAUTH2 / OAUTHBEARER: Gmail, Outlook.com, Microsoft 365. */
    OAUTH2,

    /** A bearer API token: JMAP (Fastmail). */
    API_TOKEN,
}

/** A pointer into the platform secret store. The Android Keystore holds the value; we hold the name. */
@Serializable
data class SecretRef(val key: String)

@Serializable
data class ServerEndpoint(
    val host: String,
    val port: Int,
    val security: Security,
    val username: String,
)

/** One sending identity on an account: the primary address and each alias the user may send as. */
@Serializable
data class Identity(
    val address: EmailAddress,
    val signature: String = "",
    val replyTo: EmailAddress? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class SyncPolicy(
    /** How far back the initial sync reaches. Older mail is still searchable on the server. */
    val windowDays: Int = 90,
    /** Keep an IDLE (or JMAP push) connection on the inbox while the app runs. */
    val push: Boolean = true,
    /** Background poll interval when not pushing. WorkManager clamps this to >= 15 minutes. */
    val pollMinutes: Int = 15,
    /** Download bodies of new mail eagerly (so tl;dr, OTP and categories work offline). */
    val prefetchBodies: Boolean = true,
    /** Largest attachment fetched automatically on Wi-Fi, in bytes. 0 = never automatically. */
    val autoAttachmentBytes: Long = 0,
)

@Serializable
data class Account(
    val id: Long = 0,
    val email: String,
    val displayName: String,
    /** A [dev.aarso.mail.discovery.ProviderPreset] id ("gmail", "icloud", …) or "custom". */
    val providerId: String,
    val protocol: IncomingProtocol,
    val incoming: ServerEndpoint? = null,
    val outgoing: ServerEndpoint? = null,
    /** JMAP session resource, e.g. https://api.fastmail.com/jmap/session. */
    val jmapSessionUrl: String? = null,
    val auth: AuthKind,
    /** OAuth provider key ("google", "microsoft"), when [auth] is [AuthKind.OAUTH2]. */
    val oauthProvider: String? = null,
    val secret: SecretRef,
    /** Separate SMTP secret when the provider uses distinct credentials; usually null. */
    val outgoingSecret: SecretRef? = null,
    val identities: List<Identity> = emptyList(),
    /** ARGB used to tint this account's mark wherever accounts are mixed. */
    val color: Long = 0xFF005475,
    val sync: SyncPolicy = SyncPolicy(),
    val enabled: Boolean = true,
    /** Order in the account picker. */
    val sortOrder: Int = 0,
) {
    val primaryIdentity: Identity
        get() = identities.firstOrNull { it.isDefault } ?: identities.firstOrNull()
            ?: Identity(EmailAddress(email, displayName), isDefault = true)

    /** Every address this account receives as — used to recognise "me" in To/From/Cc. */
    val myAddresses: Set<String>
        get() = (identities.map { it.address.normalized } + email.lowercase()).toSet()
}

/** The part a folder plays, independent of what the server calls it (RFC 6154 SPECIAL-USE). */
enum class MailboxRole { INBOX, SENT, DRAFTS, TRASH, JUNK, ARCHIVE, ALL, FLAGGED, IMPORTANT, OUTBOX, NONE }

data class Mailbox(
    val id: Long = 0,
    val accountId: Long,
    /** The server's full name ("[Gmail]/Sent Mail", "INBOX.Receipts", a JMAP mailbox id). */
    val remoteId: String,
    /** The leaf name a person reads ("Sent Mail", "Receipts"). */
    val name: String,
    val delimiter: Char? = '/',
    val role: MailboxRole = MailboxRole.NONE,
    val selectable: Boolean = true,
    val subscribed: Boolean = true,
    val syncEnabled: Boolean = true,
    val uidValidity: Long = 0,
    val uidNext: Long = 0,
    val highestModSeq: Long = 0,
    /** JMAP state string for Email/changes, when the account speaks JMAP. */
    val syncState: String? = null,
    val totalCount: Int = 0,
    val unreadCount: Int = 0,
    val lastSyncAt: Long = 0,
    val sortOrder: Int = 0,
) {
    /** Path segments, for drawing a hierarchy. */
    val segments: List<String>
        get() = if (delimiter == null) listOf(remoteId) else remoteId.split(delimiter)
}

/**
 * System flags as a bitmask — one integer column, one comparison per facet, and a cheap way for the
 * op queue to express "set these, clear those" against thousands of rows at once.
 */
object Flags {
    const val SEEN = 1
    const val FLAGGED = 1 shl 1
    const val ANSWERED = 1 shl 2
    const val DRAFT = 1 shl 3
    const val DELETED = 1 shl 4
    const val FORWARDED = 1 shl 5
    const val JUNK = 1 shl 6
    const val NOT_JUNK = 1 shl 7
    const val IMPORTANT = 1 shl 8

    /** IMAP system flags and the common keywords, in the casing servers send. */
    val imapNames: Map<String, Int> = linkedMapOf(
        "\\Seen" to SEEN,
        "\\Flagged" to FLAGGED,
        "\\Answered" to ANSWERED,
        "\\Draft" to DRAFT,
        "\\Deleted" to DELETED,
        "\$Forwarded" to FORWARDED,
        "\$Junk" to JUNK,
        "Junk" to JUNK,
        "\$NotJunk" to NOT_JUNK,
        "NonJunk" to NOT_JUNK,
        "\$Important" to IMPORTANT,
    )

    /** JMAP keywords (RFC 8621 §4.1.1). */
    val jmapNames: Map<String, Int> = linkedMapOf(
        "\$seen" to SEEN,
        "\$flagged" to FLAGGED,
        "\$answered" to ANSWERED,
        "\$draft" to DRAFT,
        "\$forwarded" to FORWARDED,
        "\$junk" to JUNK,
        "\$notjunk" to NOT_JUNK,
        "\$important" to IMPORTANT,
    )

    fun fromImap(names: Collection<String>): Int =
        names.fold(0) { acc, n -> acc or (imapNames.entries.firstOrNull { it.key.equals(n, true) }?.value ?: 0) }

    fun toImap(mask: Int): List<String> = buildList {
        if (mask and SEEN != 0) add("\\Seen")
        if (mask and FLAGGED != 0) add("\\Flagged")
        if (mask and ANSWERED != 0) add("\\Answered")
        if (mask and DRAFT != 0) add("\\Draft")
        if (mask and DELETED != 0) add("\\Deleted")
        if (mask and FORWARDED != 0) add("\$Forwarded")
        if (mask and JUNK != 0) add("\$Junk")
        if (mask and NOT_JUNK != 0) add("\$NotJunk")
        if (mask and IMPORTANT != 0) add("\$Important")
    }

    fun fromJmap(keywords: Collection<String>): Int =
        keywords.fold(0) { acc, k -> acc or (jmapNames[k.lowercase()] ?: 0) }

    fun toJmap(mask: Int): List<String> = jmapNames.filterValues { mask and it != 0 }.keys.toList()
}

/**
 * What a message *is about*, as the digest tabs present it. The first four are the screenshot's
 * order; the rest follow. Every assignment carries a [ClassificationSource] so the UI can show where
 * it came from, the way Hyle shows provenance.
 */
enum class Category(val label: String) {
    REPLIES("Replies"),
    TICKETS("Tickets"),
    OTP("OTP"),
    TRAVEL("Travel"),
    PEOPLE("People"),
    RECEIPTS("Receipts"),
    SHIPPING("Shipping"),
    EVENTS("Events"),
    FINANCE("Finance"),
    SECURITY("Security"),
    NEWSLETTERS("Newsletters"),
    PROMOTIONS("Promotions"),
    SOCIAL("Social"),
    UPDATES("Updates"),
    OTHER("Other");

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String): Category? = entries.firstOrNull { it.key == key.lowercase() || it.label.equals(key, true) }
    }
}

/** Where a derived fact (category, tl;dr, extracted code) came from. Provenance is never by colour alone. */
enum class ClassificationSource { NONE, HEURISTIC, ON_DEVICE_MODEL, CLOUD_MODEL, USER, RULE }

/** How much of a message is on the device. */
enum class BodyState { NONE, PARTIAL, FULL }

/** One node of a message's MIME structure, known before (or without) downloading the bytes. */
@Serializable
data class PartInfo(
    /** IMAP section path ("1", "1.2", "2"), or a JMAP blobId. */
    val partId: String,
    val mimeType: String,
    val charset: String? = null,
    val fileName: String? = null,
    val contentId: String? = null,
    val disposition: String? = null,
    val encoding: String? = null,
    val size: Long = 0,
    val blobId: String? = null,
) {
    val isAttachment: Boolean
        get() = disposition.equals("attachment", true) ||
            (fileName != null && !mimeType.startsWith("text/", true) && contentId == null) ||
            (fileName != null && disposition == null && !mimeType.startsWith("image/", true))
    val isInline: Boolean get() = contentId != null && !disposition.equals("attachment", true)
}

/** A message as the list, rules, search and digest see it — envelope, state and derived facts. */
data class MessageHeader(
    val id: Long = 0,
    val accountId: Long,
    val mailboxId: Long,
    /** IMAP UID in [mailboxId]; 0 for JMAP. */
    val uid: Long = 0,
    /** JMAP email id, POP3 UIDL, or Gmail X-GM-MSGID — the server's stable name for the message. */
    val remoteId: String? = null,
    /** Cross-folder identity on Gmail (X-GM-MSGID) so one message in three labels is one message. */
    val providerMessageId: String? = null,
    val messageId: String? = null,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    /** Local conversation key — the root message-id, or Gmail's X-GM-THRID, or a subject fallback. */
    val threadKey: String = "",
    val subject: String = "",
    val from: EmailAddress? = null,
    val sender: EmailAddress? = null,
    val replyTo: List<EmailAddress> = emptyList(),
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    /** The Date header, epoch millis. */
    val sentAt: Long = 0,
    /** When the server received it (INTERNALDATE / receivedAt), epoch millis. Drives "since yesterday". */
    val receivedAt: Long = 0,
    val size: Long = 0,
    val flags: Int = 0,
    /** Gmail labels or JMAP mailbox names beyond the containing one. */
    val labels: List<String> = emptyList(),
    val preview: String = "",
    val attachmentCount: Int = 0,
    val hasCalendar: Boolean = false,
    val listId: String? = null,
    val listUnsubscribe: String? = null,
    val listUnsubscribePost: Boolean = false,
    /** Bulk/auto-generated signals: Precedence: bulk|list|junk, Auto-Submitted, X-Auto-Response-Suppress. */
    val isBulk: Boolean = false,
    val isAutomated: Boolean = false,
    val parts: List<PartInfo> = emptyList(),
    val bodyState: BodyState = BodyState.NONE,
    // ---- derived, local ----
    val category: Category = Category.OTHER,
    val categoryConfidence: Float = 0f,
    val categorySource: ClassificationSource = ClassificationSource.NONE,
    val tldr: String? = null,
    val tldrSource: ClassificationSource = ClassificationSource.NONE,
    val otpCode: String? = null,
    val language: String? = null,
    /** 0..1, how much this deserves attention. Feeds notification and ordering decisions. */
    val priority: Float = 0f,
    val snoozedUntil: Long = 0,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    /** Count of tracking pixels/links detected in the HTML (shown and blocked in the reader). */
    val trackerCount: Int = 0,
    val authResults: String? = null,
    /** `Importance:` / `X-Priority:` as sent — a sender's own claim, weighed lightly. */
    val importanceHint: String? = null,
    /** Fingerprints of the service that sent it (github, zendesk, jira, linear, freshdesk). */
    val serviceHints: Set<String> = emptySet(),
) {
    val isSeen: Boolean get() = flags and Flags.SEEN != 0
    val isFlagged: Boolean get() = flags and Flags.FLAGGED != 0
    val isAnswered: Boolean get() = flags and Flags.ANSWERED != 0
    val isDraft: Boolean get() = flags and Flags.DRAFT != 0
    val isDeleted: Boolean get() = flags and Flags.DELETED != 0
    val hasAttachments: Boolean get() = attachmentCount > 0
    val isSnoozed: Boolean get() = snoozedUntil > 0
}

/** A downloaded body: the readable alternatives, the attachments, and anything structured inside. */
data class MessageBody(
    val messageId: Long,
    val text: String? = null,
    val html: String? = null,
    val attachments: List<PartInfo> = emptyList(),
    /** schema.org JSON-LD blocks found in the HTML (FlightReservation, Order, ParcelDelivery, …). */
    val structuredData: List<String> = emptyList(),
    val fetchedAt: Long = 0,
)

/** A conversation as the list shows it: the newest message stands for the thread. */
data class ThreadSummary(
    val threadKey: String,
    val latest: MessageHeader,
    val count: Int,
    val unreadCount: Int,
    val participants: List<EmailAddress>,
    val hasAttachments: Boolean,
    val isFlagged: Boolean,
)

/** An outgoing message, from compose to the server's Sent folder. */
@Serializable
data class Draft(
    val id: Long = 0,
    val accountId: Long,
    val from: EmailAddress,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val replyTo: List<EmailAddress> = emptyList(),
    val subject: String = "",
    val textBody: String = "",
    val htmlBody: String? = null,
    val attachments: List<DraftAttachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    /** The message this answers or forwards, so its \Answered / $Forwarded flag can be set on send. */
    val sourceMessageId: Long? = null,
    val kind: DraftKind = DraftKind.NEW,
    /** Send at this instant (send-later). 0 = as soon as possible. */
    val sendAt: Long = 0,
    /** Ask for a nudge if nobody replies by this instant. 0 = no follow-up reminder. */
    val followUpAt: Long = 0,
    val requestReadReceipt: Boolean = false,
    val updatedAt: Long = 0,
)

enum class DraftKind { NEW, REPLY, REPLY_ALL, FORWARD, EDIT_AS_NEW }

/** An attachment on a draft: a local file the platform can open, with its declared type. */
@Serializable
data class DraftAttachment(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val inline: Boolean = false,
    val contentId: String? = null,
)

/** A known correspondent, learned from mail; feeds autocomplete and "people" signals. */
data class Contact(
    val address: String,
    val name: String?,
    /** How many messages I have sent to them — the strongest "this is a real person to me" signal. */
    val sentCount: Int,
    val receivedCount: Int,
    val lastContactAt: Long,
)
