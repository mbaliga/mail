package dev.aarso.mail.app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.aarso.mail.app.MailApp
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.net.AuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * "Always-on" push: one IMAP IDLE (or JMAP EventSource) connection per account, held by a
 * data-sync foreground service so new mail lands within seconds instead of at the next poll.
 *
 * Off by default — it costs a persistent notification and some battery. Android 15 caps data-sync
 * services at six hours a day; when the system calls [onTimeout] the service stops and the
 * periodic worker carries on polling until the app is next opened.
 */
class PushService : LifecycleService() {

    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val graph = (application as MailApp).graph
        if (intent?.action == ACTION_STOP || !graph.settings.value.alwaysOnPush) {
            stopSelf()
            return START_NOT_STICKY
        }
        val count = graph.accountsNow().count { it.enabled && it.protocol != IncomingProtocol.POP3 }
        try {
            startForeground(Notifier.SYNC_ID, graph.notifier.syncNotification(count), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
            // Not allowed to start from the background right now (Android 12+): fall back to polling.
            stopSelf()
            return START_NOT_STICKY
        }
        if (job == null) job = lifecycleScope.launch { hold(graph) }
        return START_STICKY
    }

    private suspend fun hold(graph: dev.aarso.mail.app.AppGraph) {
        graph.store.observeAccounts()
            .map { all -> all.filter { it.enabled && it.protocol != IncomingProtocol.POP3 } }
            .distinctUntilChanged()
            .collectLatest { accounts ->
                coroutineScope { for (a in accounts) launch { loop(graph, a) } }
            }
    }

    private suspend fun loop(graph: dev.aarso.mail.app.AppGraph, account: Account) {
        var backoff = 5_000L
        while (coroutineContext.isActive) {
            try {
                val ctx = coroutineContext
                graph.sync.push(account) { ctx.isActive }
                backoff = 5_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationException) {
                return // the account needs the person; the UI shows it
            } catch (_: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15 * 60_000L)
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "dev.aarso.mail.PUSH_STOP"

        /** Start (from a foreground context) or stop the service to match the setting. */
        fun sync(context: Context, enabled: Boolean) {
            val i = Intent(context, PushService::class.java)
            if (enabled) {
                runCatching { ContextCompat.startForegroundService(context, i) }
            } else {
                runCatching { context.startService(i.setAction(ACTION_STOP)) }
            }
        }
    }
}
