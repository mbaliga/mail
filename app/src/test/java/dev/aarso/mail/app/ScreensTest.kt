package dev.aarso.mail.app

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.aarso.mail.app.ui.ActivityBridge
import dev.aarso.mail.app.ui.AppRoot
import dev.aarso.mail.app.ui.AppState
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.LocalBridge
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.compose.ComposeScreen
import dev.aarso.mail.app.ui.compose.WriteRoom
import dev.aarso.mail.app.ui.find.FindRoom
import dev.aarso.mail.app.ui.list.MailboxScreen
import dev.aarso.mail.app.ui.places.PlacesRoom
import dev.aarso.mail.app.ui.reader.ReaderScreen
import dev.aarso.mail.app.ui.rules.RulesRoom
import dev.aarso.mail.app.ui.settings.SettingsScreen
import dev.aarso.mail.app.ui.theme.MailTheme
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.ClassificationSource
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.Flags
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import dev.aarso.mail.model.MessageBody
import dev.aarso.mail.model.MessageHeader
import dev.aarso.mail.model.RuleAction
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.model.Security
import dev.aarso.mail.model.ServerEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens, on a real store, on the JVM — the check that the home screen still
 * reads like its reference design, and that nothing throws while composing. Images land in
 * app/build/screens/ for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ScreensTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private lateinit var state: AppState
    private var firstId = 0L

    private val lines = listOf(
        Triple("Fin, Anthropic's chatbot", Category.REPLIES, "Fin, Anthropic's chatbot, says your refund for the duplicate charge is approved and arrives in 5–10 days."),
        Triple("Priya Raman", Category.REPLIES, "Priya agrees to Thursday at 3 and asks you to bring the signed lease."),
        Triple("Marcus Lee", Category.REPLIES, "Marcus sent the revised deck and needs your comments by Friday noon."),
        Triple("GitHub", Category.TICKETS, "GitHub: 2 reviewers approved your pull request #482; one check is still failing."),
        Triple("Linear", Category.TICKETS, "Linear: ENG-1931 was assigned to you, due Monday."),
        Triple("Zendesk", Category.TICKETS, "Support replied to ticket 88213 asking for your order number."),
        Triple("Apple", Category.OTP, "Apple sent your sign-in code 482 913; it expires in 10 minutes."),
        Triple("Lufthansa", Category.TRAVEL, "Lufthansa: check-in is open for LH 401 to Frankfurt tomorrow at 18:40, seat 23A."),
        Triple("Airbnb", Category.TRAVEL, "Airbnb confirms 3 nights in Lisbon from 14 Nov; check-in after 15:00."),
        Triple("Amazon", Category.SHIPPING, "Amazon says your headphones arrive today between 14:00 and 18:00."),
        Triple("Monzo", Category.FINANCE, "Monzo: your card was charged £42.10 at Waitrose."),
        Triple("Stratechery", Category.NEWSLETTERS, "Ben Thompson on why aggregators win the AI distribution fight."),
    )

    @Before
    fun seed() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.getDatabasePath("mail.db").path).delete()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        graph = AppGraph(app)
        runBlocking {
            val acct = graph.store.saveAccount(
                Account(
                    email = "mi@gmail.com", displayName = "Mi", providerId = "gmail", protocol = IncomingProtocol.IMAP,
                    incoming = ServerEndpoint("imap.gmail.com", 993, Security.SSL_TLS, "mi@gmail.com"),
                    outgoing = ServerEndpoint("smtp.gmail.com", 465, Security.SSL_TLS, "mi@gmail.com"),
                    auth = AuthKind.APP_PASSWORD, secret = SecretRef("test"),
                ),
            )
            val boxes = graph.store.upsertMailboxes(
                acct.id,
                listOf(
                    Mailbox(accountId = acct.id, remoteId = "INBOX", name = "Inbox", role = MailboxRole.INBOX),
                    Mailbox(accountId = acct.id, remoteId = "Sent", name = "Sent", role = MailboxRole.SENT),
                    Mailbox(accountId = acct.id, remoteId = "Archive", name = "Archive", role = MailboxRole.ARCHIVE),
                    Mailbox(accountId = acct.id, remoteId = "Trash", name = "Trash", role = MailboxRole.TRASH),
                    Mailbox(accountId = acct.id, remoteId = "Receipts", name = "Receipts"),
                ),
                removeOthers = false,
            )
            val inbox = boxes.first { it.role == MailboxRole.INBOX }
            val now = System.currentTimeMillis()
            val headers = (0 until 60).map { i ->
                val (who, cat, tldr) = lines[i % lines.size]
                MessageHeader(
                    accountId = acct.id, mailboxId = inbox.id, uid = 1000L + i, messageId = "<m$i@test>", threadKey = "t$i",
                    subject = tldr.substringAfter(": ").take(48), from = EmailAddress("${who.lowercase().replace(Regex("[^a-z]"), "")}@example.com", who),
                    to = listOf(EmailAddress("mi@gmail.com", "Mi")), sentAt = now - i * 17 * 60_000L, receivedAt = now - i * 17 * 60_000L,
                    flags = if (i % 3 == 0) Flags.SEEN else 0, preview = tldr, category = cat, categorySource = ClassificationSource.HEURISTIC,
                    tldr = tldr, tldrSource = if (i % 2 == 0) ClassificationSource.ON_DEVICE_MODEL else ClassificationSource.HEURISTIC,
                    otpCode = if (cat == Category.OTP) "482913" else null, size = 24_000L + i * 1000, attachmentCount = if (i % 5 == 0) 1 else 0,
                )
            }
            val stored = graph.store.insertMessages(headers)
            for (h in stored) graph.store.updateDerived(h.id, category = h.category, tldr = h.tldr, tldrSource = h.tldrSource, otp = h.otpCode)
            firstId = stored.first().id
            graph.store.saveBody(MessageBody(firstId, text = "Hi Mi,\n\nGood news — the duplicate charge from 2 November has been refunded. It can take 5–10 working days to appear on your statement.\n\nIs there anything else I can help with?\n\nFin\nAnthropic support\n\n> On Mon, Mi wrote:\n> I was charged twice this month.", fetchedAt = now), stored.first())
            graph.store.saveRule(MailRule(name = "Old newsletters", query = "category:newsletters older_than:7d in:inbox", actions = listOf(RuleAction.Archive)))
            graph.search.refreshAccounts()
        }
        state = AppState(graph, graph.scope)
    }

    private fun show(name: String, waitFor: String? = null, content: @Composable () -> Unit) {
        val bridge = object : ActivityBridge {
            override val activity get() = rule.activity
            override suspend fun startForResult(intent: Intent) = ActivityResult(0, null)
            override suspend fun startIntentSender(pending: PendingIntent) = ActivityResult(0, null)
            override suspend fun pickDocuments(types: Array<String>, multiple: Boolean) = emptyList<Uri>()
            override suspend fun createDocument(name: String, mime: String): Uri? = null
            override fun launch(intent: Intent) {}
            override fun requestNotifications() {}
        }
        rule.setContent {
            MailTheme { CompositionLocalProvider(LocalApp provides state, LocalBridge provides bridge) { content() } }
        }
        if (waitFor != null) rule.waitUntil(10_000) { rule.onAllNodesWithText(waitFor, substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Test fun home() = show("home", waitFor = "REPLIES") { AppRoot(state) }

    @Test fun homeSubjects() {
        graph.updateSettings { it.copy(tldr = false) }
        show("home-subjects", waitFor = "REPLIES") { AppRoot(state) }
    }

    @Test fun homeLongPhrase() {
        graph.updateSettings { it.copy(digestWindow = dev.aarso.mail.digest.DigestWindow.SINCE_LAST_LOOK) }
        show("home-long-phrase", waitFor = "REPLIES") { AppRoot(state) }
    }

    @Test fun selecting() {
        show("mailbox", waitFor = "messages") { MailboxScreen(Screen.Mailbox("Inbox", "in:inbox")) {} }
        rule.onAllNodesWithText("Priya agrees", substring = true)[0].performTouchInput { longClick() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("selected", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Marcus sent", substring = true)[0].performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screens/mailbox-selecting.png")
    }

    @Test fun places() = show("room-places", waitFor = "Inbox") { PlacesRoom {} }

    @Test fun find() = show("room-find", waitFor = "TRY") { FindRoom {} }

    @Test fun rules() = show("room-rules", waitFor = "Old newsletters") { RulesRoom {} }

    @Test fun write() = show("room-write", waitFor = "New message") { WriteRoom {} }

    @Test fun mailbox() = show("mailbox", waitFor = "messages") { MailboxScreen(Screen.Mailbox("Inbox", "in:inbox")) {} }

    @Test fun reader() = show("reader", waitFor = "refund") { ReaderScreen(firstId, emptyList()) {} }

    @Test fun compose() {
        val a = runBlocking { graph.store.accounts().first() }
        show("compose", waitFor = "Subject") { ComposeScreen(Drafts.new(a, to = listOf(EmailAddress("priya@example.com", "Priya Raman")))) {} }
    }

    @Test fun settings() = show("settings", waitFor = "ACCOUNTS") { SettingsScreen {} }
}
