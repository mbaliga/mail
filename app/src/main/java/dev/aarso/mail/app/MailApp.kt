package dev.aarso.mail.app

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.mail.app.platform.Work

open class MailApp : Application() {

    private val ready = kotlinx.coroutines.CompletableDeferred<AppGraph>()

    /**
     * Everything the app owns. Built on a background thread from [onCreate] — opening the database,
     * loading the SQLite natives and the Keystore key take a few tens of milliseconds, and the
     * framework meanwhile creates the Activity. Whoever needs it first waits for the rest.
     */
    val graph: AppGraph
        get() = if (ready.isCompleted) ready.getCompleted() else kotlinx.coroutines.runBlocking { ready.await() }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        // Lets code and native libraries from on-demand feature modules load (Play bundles only).
        if (BuildConfig.SPLIT_ON_DEVICE) com.google.android.play.core.splitcompat.SplitCompat.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        val label = getString(R.string.app_name)
        CrashRecovery.install(this, label)
        Thread({
            try {
                val g = AppGraph(this)
                g.notifier.createChannels()
                Work.schedulePeriodic(this, g.settings.value.pollMinutes)
                g.start()
                ready.complete(g)
            } catch (t: Throwable) {
                // A store that cannot open (disk full, corrupted file) gets the recovery screen, not a loop.
                CrashRecovery.captureInitError(this, label, t)
                ready.completeExceptionally(t)
            }
        }, "graph-init").start()
    }
}
