package dev.aarso.mail.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.aarso.mail.app.AppSettings
import dev.aarso.mail.app.MailApp
import dev.aarso.mail.app.MainActivity
import dev.aarso.mail.app.R
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.NotifyLevel
import dev.aarso.mail.rules.RuleOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Notifications as a filter, not a firehose: every message gets a level — from a rule if one said
 * so, else from its category's setting — and only URGENT and NORMAL make a sound. Codes arrive with
 * a one-tap copy and vanish when they expire; replies carry an inline reply box; quiet hours hold
 * everything but codes and replies.
 */
class Notifier(private val context: Context) {

    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        val m = context.getSystemService(NotificationManager::class.java)
        m.createNotificationChannels(
            listOf(
                NotificationChannel(CH_URGENT, "Codes and replies", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "One-time codes, replies to you, and anything a rule marks urgent"
                },
                NotificationChannel(CH_MAIL, "New mail", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_SILENT, "Quiet mail", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(true) },
                NotificationChannel(CH_REMINDERS, "Snoozes and follow-ups", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_SYNC, "Staying connected", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
                NotificationChannel(CH_SEND, "Sending", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    private fun level(h: MessageHeader, outcome: RuleOutcome?, settings: AppSettings): NotifyLevel {
        if (h.muted) return NotifyLevel.NEVER
        val base = outcome?.notify?.get(h.id) ?: settings.notify[h.category] ?: NotifyLevel.SILENT
        if (settings.quietHours && inQuiet(settings) && base == NotifyLevel.NORMAL) return NotifyLevel.SILENT
        if (settings.quietHours && inQuiet(settings) && base == NotifyLevel.URGENT && h.category !in setOf(Category.OTP, Category.REPLIES, Category.SECURITY)) return NotifyLevel.SILENT
        return base
    }

    private fun inQuiet(s: AppSettings): Boolean {
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return if (s.quietFrom <= s.quietTo) now in s.quietFrom until s.quietTo else now >= s.quietFrom || now < s.quietTo
    }

    fun notifyNew(account: Account, fresh: List<MessageHeader>, outcome: RuleOutcome?, settings: AppSettings) {
        if (!nm.areNotificationsEnabled()) return
        val unseen = fresh.filter { !it.isSeen && !it.isDraft }
        var posted = 0
        for (h in unseen.sortedBy { it.receivedAt }) {
            val lvl = level(h, outcome, settings)
            if (lvl == NotifyLevel.NEVER) continue
            post(account, h, lvl)
            posted++
        }
        if (posted > 1) postSummary(account, unseen.size)
    }

    private fun contentIntent(messageId: Long): PendingIntent = PendingIntent.getActivity(
        context, messageId.toInt(),
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_MESSAGE).putExtra(MainActivity.EXTRA_MESSAGE_ID, messageId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String, messageId: Long, mutable: Boolean = false, extra: Pair<String, String>? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context, (action.hashCode() * 31 + messageId).toInt(),
            Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, messageId)
                .apply { extra?.let { putExtra(it.first, it.second) } },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
        )

    private fun post(account: Account, h: MessageHeader, lvl: NotifyLevel) {
        val channel = when (lvl) { NotifyLevel.URGENT -> CH_URGENT; NotifyLevel.NORMAL -> CH_MAIL; else -> CH_SILENT }
        val from = h.from?.displayName() ?: "Someone"
        val line = h.tldr ?: h.subject
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setColor(0xFF1F3354.toInt())
            .setContentTitle(if (h.category == Category.OTP && h.otpCode != null) "${h.otpCode}  ·  $from" else from)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(h.subject.takeIf { it != line }, line, h.preview.takeIf { it.isNotBlank() && it != line }).joinToString("\n")))
            .setWhen(h.receivedAt)
            .setShowWhen(true)
            .setGroup("acct:${account.id}")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setContentIntent(contentIntent(h.id))
            .setSubText(account.email)
            .setPriority(if (lvl == NotifyLevel.URGENT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        val code = h.otpCode
        if (h.category == Category.OTP && code != null) {
            b.addAction(0, "Copy $code", actionIntent(ACTION_COPY_CODE, h.id, extra = EXTRA_CODE to code))
            b.setTimeoutAfter(15 * 60_000L)
        } else {
            b.addAction(0, "Archive", actionIntent(ACTION_ARCHIVE, h.id))
            b.addAction(0, "Mark read", actionIntent(ACTION_READ, h.id))
            if (!h.isBulk && !h.isAutomated) {
                val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to ${h.from?.shortName() ?: from}").build()
                b.addAction(
                    NotificationCompat.Action.Builder(0, "Reply", actionIntent(ACTION_REPLY, h.id, mutable = true))
                        .addRemoteInput(input).setAllowGeneratedReplies(true).build(),
                )
            }
        }
        runCatching { nm.notify(h.id.toInt(), b.build()) }
    }

    private fun postSummary(account: Account, count: Int) {
        val b = NotificationCompat.Builder(context, CH_SILENT)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setColor(0xFF1F3354.toInt())
            .setContentTitle("$count new")
            .setSubText(account.email)
            .setGroup("acct:${account.id}")
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, account.id.toInt(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        runCatching { nm.notify(SUMMARY_BASE + account.id.toInt(), b.build()) }
    }

    fun notifyReminder(h: MessageHeader, kind: String) {
        val title = if (kind == "followup") "No reply yet" else "Back from snooze"
        val b = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setColor(0xFF1F3354.toInt())
            .setContentTitle(title)
            .setContentText(h.tldr ?: h.subject)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(h.id))
        runCatching { nm.notify(REMINDER_BASE + h.id.toInt(), b.build()) }
    }

    fun notifySendFailed(subject: String, error: String) {
        val b = NotificationCompat.Builder(context, CH_SEND)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("Not sent: $subject")
            .setContentText(error)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_OUTBOX), PendingIntent.FLAG_IMMUTABLE))
        runCatching { nm.notify(SEND_FAILED, b.build()) }
    }

    fun syncNotification(accounts: Int) = NotificationCompat.Builder(context, CH_SYNC)
        .setSmallIcon(R.drawable.ic_stat_mail)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(if (accounts == 1) "1 inbox" else "$accounts inboxes")
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(PendingIntent.getActivity(context, 9, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    fun cancel(messageId: Long) = nm.cancel(messageId.toInt())

    companion object {
        const val CH_URGENT = "urgent"
        const val CH_MAIL = "mail"
        const val CH_SILENT = "silent"
        const val CH_REMINDERS = "reminders"
        const val CH_SYNC = "sync"
        const val CH_SEND = "send"
        const val ACTION_ARCHIVE = "dev.aarso.mail.ARCHIVE"
        const val ACTION_READ = "dev.aarso.mail.READ"
        const val ACTION_REPLY = "dev.aarso.mail.REPLY"
        const val ACTION_COPY_CODE = "dev.aarso.mail.COPY_CODE"
        const val EXTRA_ID = "id"
        const val EXTRA_CODE = "code"
        const val KEY_REPLY = "reply"
        const val SUMMARY_BASE = 1_000_000
        const val REMINDER_BASE = 2_000_000
        const val SEND_FAILED = 3_000_001
        const val SYNC_ID = 3_000_002
    }
}

/** Archive / read / reply / copy-code straight from the shade, through the same actions the app uses. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as MailApp).graph
        val id = intent.getLongExtra(Notifier.EXTRA_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Notifier.ACTION_ARCHIVE -> graph.actions.archive(listOf(id))
                    Notifier.ACTION_READ -> graph.actions.markRead(listOf(id))
                    Notifier.ACTION_COPY_CODE -> {
                        val code = intent.getStringExtra(Notifier.EXTRA_CODE) ?: return@launch
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newPlainText("Code", code)
                        // Android 13+: keep the code out of the clipboard preview overlay.
                        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                        cm.setPrimaryClip(clip)
                        graph.actions.markRead(listOf(id))
                    }
                    Notifier.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                        if (!text.isNullOrBlank()) graph.quickReply(id, text)
                    }
                }
                graph.notifier.cancel(id)
            } finally {
                pending.finish()
            }
        }
    }
}
