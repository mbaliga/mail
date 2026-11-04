package dev.aarso.mail.app.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.aarso.mail.app.MailApp
import dev.aarso.mail.net.AuthenticationException
import java.util.concurrent.TimeUnit

/**
 * Everything that runs without the app on screen, as WorkManager jobs:
 *
 * - **sync**: periodic (the person's interval, 15 minutes minimum) plus one-shot "now" runs;
 * - **flush**: pushes queued local changes a moment after a burst of actions, so archiving 2,000
 *   messages is one round of server commands rather than 2,000;
 * - **outbox**: sends what is due (after the undo window, or at a scheduled send time);
 * - **reminders**: snoozes coming back and follow-ups with no reply.
 *
 * All of it is idempotent: a job that runs twice does the work once, because every step reads the
 * store's own state (pending ops, outbox rows, reminder rows) rather than its input data.
 */
object Work {
    private const val PERIODIC = "sync.periodic"
    private const val SYNC_NOW = "sync.now"
    private const val FLUSH = "flush"
    private const val OUTBOX = "outbox"
    private const val REMINDERS = "reminders"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context, minutes: Int) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun syncNow(context: Context, accountId: Long? = null, inboxOnly: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(KEY_ACCOUNT to (accountId ?: -1L), KEY_INBOX_ONLY to inboxOnly))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SYNC_NOW + (accountId ?: ""), ExistingWorkPolicy.KEEP, req)
    }

    /** Debounced: each new action pushes the flush back by [delaySeconds], so a burst becomes one run. */
    fun flushSoon(context: Context, accountIds: Set<Long>, delaySeconds: Long = 3) {
        val req = OneTimeWorkRequestBuilder<FlushWorker>()
            .setConstraints(online)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_ACCOUNTS to accountIds.toLongArray()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FLUSH, ExistingWorkPolicy.REPLACE, req)
    }

    /** Wake the outbox at [at] (epoch ms) — the end of the undo window or a scheduled send. */
    fun sendAt(context: Context, at: Long) {
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(online)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(OUTBOX, ExistingWorkPolicy.REPLACE, req)
    }

    fun remindAt(context: Context, at: Long) {
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(REMINDERS, ExistingWorkPolicy.REPLACE, req)
    }

    internal const val KEY_ACCOUNT = "account"
    internal const val KEY_ACCOUNTS = "accounts"
    internal const val KEY_INBOX_ONLY = "inboxOnly"
}

private val Context.graph get() = (applicationContext as MailApp).graph

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val accountId = inputData.getLong(Work.KEY_ACCOUNT, -1)
        val inboxOnly = inputData.getBoolean(Work.KEY_INBOX_ONLY, false)
        val reports = if (accountId > 0) {
            listOfNotNull(g.store.account(accountId)?.let { g.sync.sync(it, inboxOnly) })
        } else g.sync.syncAll(inboxOnly)
        g.afterSync()
        // A wrong password will not fix itself on retry; a dropped connection might.
        val transient = reports.any { it.error != null && it.error !is AuthenticationException }
        return if (transient && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val ids = inputData.getLongArray(Work.KEY_ACCOUNTS)?.toSet().orEmpty()
        val accounts = g.store.accounts().filter { it.enabled && (ids.isEmpty() || it.id in ids) }
        var failed = false
        for (a in accounts) {
            runCatching { g.sync.flushOps(a) }.onFailure { failed = true }
        }
        // Ops that failed stay queued with their own backoff; the next sync flushes them too.
        return if (failed && g.store.pendingOpCount() > 0 && runAttemptCount < 5) Result.retry() else Result.success()
    }
}

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val result = g.outbox.sendDue()
        for ((item, error) in result.failed) g.notifier.notifySendFailed(item.draft.subject.ifBlank { "(no subject)" }, error)
        g.scheduleOutbox()
        return Result.success()
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.graph.runReminders()
        return Result.success()
    }
}
