package dev.aarso.mail.app

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.mail.app.platform.Work

class MailApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val label = getString(R.string.app_name)
        CrashRecovery.install(this, label)
        try {
            graph = AppGraph(this)
        } catch (t: Throwable) {
            // A store that cannot open (disk full, corrupted file) gets the recovery screen, not a loop.
            CrashRecovery.captureInitError(this, label, t)
            throw t
        }
        graph.notifier.createChannels()
        Work.schedulePeriodic(this, graph.settings.value.pollMinutes)
        graph.start()
    }
}
