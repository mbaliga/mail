package dev.aarso.mail.app

import dev.aarso.mail.ai.AiSettings
import dev.aarso.mail.ai.CloudEngineConfig
import dev.aarso.mail.digest.DigestWindow
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.NotifyLevel
import kotlinx.serialization.Serializable

/** What a swipe on a list row does. */
@Serializable
enum class SwipeAction(val label: String) { ARCHIVE("Archive"), TRASH("Delete"), READ("Read / unread"), SNOOZE("Snooze"), STAR("Star"), NONE("Nothing") }

/**
 * Everything the person can tune, in one serializable value persisted in the store's settings table
 * (so it is covered by the same transactions and observation as the mail itself).
 */
@Serializable
data class AppSettings(
    // ---- Home ----
    val digestWindow: DigestWindow = DigestWindow.SINCE_YESTERDAY,
    /** Empty = every account. */
    val digestAccounts: Set<Long> = emptySet(),
    /** Show model-or-heuristic one-liners (true) or sender / subject / preview (false). */
    val tldr: Boolean = true,
    val lastLookAt: Long = 0,
    // ---- Lists ----
    val swipeRight: SwipeAction = SwipeAction.ARCHIVE,
    val swipeLeft: SwipeAction = SwipeAction.SNOOZE,
    val conversationView: Boolean = true,
    // ---- Reading ----
    /** Load remote images automatically (off: images wait for a tap; trackers are always removed). */
    val loadRemoteImages: Boolean = false,
    // ---- Sending ----
    val undoSendSeconds: Int = 10,
    // ---- Notifications ----
    val notify: Map<Category, NotifyLevel> = mapOf(
        Category.OTP to NotifyLevel.URGENT, Category.REPLIES to NotifyLevel.URGENT, Category.PEOPLE to NotifyLevel.NORMAL,
        Category.SECURITY to NotifyLevel.URGENT, Category.TICKETS to NotifyLevel.NORMAL, Category.TRAVEL to NotifyLevel.NORMAL,
        Category.FINANCE to NotifyLevel.NORMAL, Category.EVENTS to NotifyLevel.NORMAL, Category.SHIPPING to NotifyLevel.SILENT,
        Category.RECEIPTS to NotifyLevel.SILENT, Category.UPDATES to NotifyLevel.SILENT, Category.SOCIAL to NotifyLevel.NEVER,
        Category.NEWSLETTERS to NotifyLevel.NEVER, Category.PROMOTIONS to NotifyLevel.NEVER, Category.OTHER to NotifyLevel.SILENT,
    ),
    /** Quiet hours, minutes from midnight; equal = off. Urgent codes still come through. */
    val quietFrom: Int = 22 * 60,
    val quietTo: Int = 7 * 60,
    val quietHours: Boolean = false,
    // ---- Sync ----
    /** Hold an IDLE / EventSource connection in a foreground service for instant mail. */
    val alwaysOnPush: Boolean = false,
    val pollMinutes: Int = 15,
    // ---- Intelligence ----
    val ai: AiSettings = AiSettings(),
    val cloudEngines: List<CloudEngineConfig> = emptyList(),
    /** Path to a local Gemma model file (.task / .bin) for devices without AICore. */
    val localModelPath: String? = null,
    // ---- Misc ----
    val feedbackOptIn: Boolean = false,
    val onboarded: Boolean = false,
)
