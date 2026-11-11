package dev.aarso.mail.app

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.model.Security
import dev.aarso.mail.model.ServerEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real Application and Activity, launched the way the launcher launches them. */
class TestMailApp : MailApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
        super.onCreate()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = TestMailApp::class)
class StartupTest {

    private fun idle() = repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }

    @Test fun firstRunShowsWelcome() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            idle()
            s.onActivity { a -> assertFalse(a.isFinishing) }
            s.onActivity { a -> a.window.decorView.captureRoboImage("build/screens/welcome.png") }
        }
    }

    @Test fun withAnAccountOpensHome() {
        val app = ApplicationProvider.getApplicationContext<MailApp>()
        runBlocking {
            app.graph.store.saveAccount(
                Account(
                    email = "mi@icloud.com", displayName = "Mi", providerId = "icloud", protocol = IncomingProtocol.IMAP,
                    // An unroutable host, so the on-start sync fails fast and quietly.
                    incoming = ServerEndpoint("127.0.0.1", 1, Security.SSL_TLS, "mi@icloud.com"),
                    outgoing = ServerEndpoint("127.0.0.1", 1, Security.STARTTLS, "mi@icloud.com"),
                    auth = AuthKind.APP_PASSWORD, secret = SecretRef("t"),
                ),
            )
        }
        app.graph.updateSettings { it.copy(onboarded = true) }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            idle()
            s.onActivity { a -> assertTrue(!a.isFinishing) }
            s.onActivity { a -> a.window.decorView.captureRoboImage("build/screens/home-empty.png") }
        }
    }
}
