package dev.aarso.mail.app

import org.junit.Assert.assertEquals
import kotlinx.coroutines.flow.first
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import dev.aarso.mail.app.ui.compose.AttachMailContent
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
        // The provider strip drifts on its own; frozen here so captures stay still (the drift has its own test).
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
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
            graph.store.recordContacts(listOf(EmailAddress("priya@example.com", "Priya Raman"), EmailAddress("marcus@example.com", "Marcus Lee")), sentByMe = true, at = now)
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
            override suspend fun requestPermission(permission: String) = false
        }
        rule.setContent {
            MailTheme { CompositionLocalProvider(LocalApp provides state, LocalBridge provides bridge) { content() } }
        }
        if (waitFor != null) rule.waitUntil(30_000) { rule.onAllNodesWithText(waitFor, substring = true).fetchSemanticsNodes().isNotEmpty() }
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

    @Test fun providerStripDriftsAndIsDraggable() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val providers = dev.aarso.mail.discovery.Providers.featured
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MailTheme { androidx.compose.foundation.layout.Box(Modifier.testTag("strip")) { dev.aarso.mail.app.ui.components.ProviderStrip(providers, null, {}) } }
        }
        fun x() = rule.onNodeWithContentDescription("Gmail").fetchSemanticsNode().positionInRoot.x
        rule.mainClock.advanceTimeBy(200)
        val a = x()
        rule.mainClock.advanceTimeBy(3_000)
        val b = x()
        assertTrue("the strip drifts by itself ($a -> $b)", b < a - 20f)
        rule.onNodeWithTag("strip").performTouchInput { swipeLeft() }
        rule.mainClock.advanceTimeBy(400)
        val c = x()
        assertTrue("a swipe moves it ($b -> $c)", kotlin.math.abs(c - b) > 100f)
    }

    @Test fun firstRun() = show("first-run", waitFor = "set up yet") { dev.aarso.mail.app.ui.components.FirstRun {} }

    @Test fun setup() = show("setup", waitFor = "WORKS WITH") { dev.aarso.mail.app.ui.accounts.AccountSetupScreen(null) {} }

    @Test fun setupStepTwo() {
        val id = runBlocking { graph.store.accounts().first().id }
        show("setup-sign-in", waitFor = "Sign in") { dev.aarso.mail.app.ui.accounts.AccountSetupScreen(id) {} }
    }

    @Test fun brandSplash() = show("brand-splash") { dev.aarso.mail.app.ui.components.BrandSplash() }

    @Test fun brandIcon() = show("brand-icon") {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Box(Modifier.size(240.dp).clip(RoundedCornerShape(56.dp)).background(Color(0xFF1F3354))) {
                Image(painterResource(dev.aarso.mail.app.R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
        }
    }

    @Test fun ruleEditor() = show("rule-editor", waitFor = "Save") {
        dev.aarso.mail.app.ui.rules.RuleEditor(MailRule(name = "Old newsletters", query = "category:newsletters older_than:7d in:inbox", actions = listOf(RuleAction.Archive))) {}
    }

    @Test fun emptyFolder() = show("mailbox-empty", waitFor = "is empty") { MailboxScreen(Screen.Mailbox("Trash", "in:trash")) {} }

    @Test fun outbox() = show("outbox-empty", waitFor = "waiting to send") { dev.aarso.mail.app.ui.compose.OutboxScreen {} }

    @Test fun attachSheet() = show("attach-sheet", waitFor = "Attach mail") {
        Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 24.dp, vertical = 32.dp)) {
            AttachMailContent { _, _, _ -> }
        }
    }

    @Test fun findTypeahead() {
        show("find-typeahead", waitFor = "TRY") { FindRoom {} }
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("from:pri")
        rule.waitUntil(8_000) { rule.onAllNodesWithText("Priya", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screens/find-typeahead.png")
    }

    @Test fun findResults() {
        show("find-results", waitFor = "TRY") { FindRoom {} }
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("refund")
        rule.waitUntil(8_000) { rule.onAllNodesWithText("Refine", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screens/find-results.png")
    }

    @Test fun places() = show("room-places", waitFor = "Inbox") { PlacesRoom({}, {}) }

    @Test fun find() = show("room-find", waitFor = "TRY") { FindRoom {} }

    @Test fun rules() {
        assertTrue("the seeded rule is in the store", runBlocking { graph.store.rules() }.any { it.name == "Old newsletters" })
        show("room-rules", waitFor = "Old newsletters") { RulesRoom {} }
    }

    @Test fun write() = show("room-write", waitFor = "New message") { WriteRoom {} }

    @Test fun mailbox() = show("mailbox", waitFor = "messages") { MailboxScreen(Screen.Mailbox("Inbox", "in:inbox")) {} }

    /** A conversation of four messages, so the reader has cards to stack. */
    private fun conversation(): Long = runBlocking {
        val first = graph.store.header(firstId)!!
        val now = System.currentTimeMillis()
        val extra = listOf("Priya Raman" to "Thanks Fin — can you confirm the amount?", "Fin" to "Confirmed: the full charge is refunded.", "Priya Raman" to "Great, that settles it.").mapIndexed { i, (who, text) ->
            first.copy(
                id = 0, uid = 5000L + i, messageId = "<c$i@test>", threadKey = first.threadKey, subject = first.subject,
                from = EmailAddress("${who.lowercase().replace(" ", "")}@example.com", who), receivedAt = now - (3 - i) * 60_000L, sentAt = now - (3 - i) * 60_000L,
                preview = text, tldr = text,
            )
        }
        graph.store.insertMessages(extra).forEach { graph.store.saveBody(MessageBody(it.id, text = it.preview + "\n\n> On Mon, Fin wrote:\n> earlier words"), it) }
        firstId
    }

    @Test fun readerCarouselAndCards() {
        val id = conversation()
        val ids = runBlocking { graph.store.observeMessages { graph.store.threadMessages(graph.store.header(id)!!.threadKey, graph.store.header(id)!!.accountId) }.first() }.map { it.id }
        show("reader-carousel", waitFor = "refund") { ReaderScreen(id, listOf(id) + ids.filter { it != id }.take(1) + listOf(id + 1)) {} }
    }

    @Test fun tabsOpenAndCloseLikeTabs() {
        val a = runBlocking { graph.store.accounts().first() }
        val s = state
        assertEquals(1, s.tabs.size)
        s.open(Screen.Reader(firstId)); s.open(Screen.Reader(firstId + 1))
        assertEquals("two conversations, two tabs", 3, s.tabs.size)
        s.open(Screen.Reader(firstId))
        assertEquals("opening one again switches to it", 3, s.tabs.size)
        s.compose(Drafts.new(a))
        assertEquals(4, s.tabs.size)
        s.open(Screen.Settings)
        assertTrue("navigation goes to Home", s.active === s.home && s.home.stack.last() == Screen.Settings)
        s.activate(s.tabs.last())
        assertTrue(s.back())
        assertEquals("back from a tab's only page closes the tab", 3, s.tabs.size)
        assertTrue("and lands on a tab that is still open", s.active in s.tabs)
    }

    @Test fun tabBarRender() {
        s0()
        show("tabs", waitFor = "Outbox") {
            AppRoot(state)
        }
    }

    private fun s0() {
        state.open(Screen.Reader(firstId)); state.open(Screen.Outbox)
    }

    @Test fun reader() = show("reader", waitFor = "refund") { ReaderScreen(firstId, emptyList()) {} }

    @Test fun compose() {
        val a = runBlocking { graph.store.accounts().first() }
        show("compose", waitFor = "Subject") { ComposeScreen(Drafts.new(a, to = listOf(EmailAddress("priya@example.com", "Priya Raman")))) {} }
    }

    @Test fun settings() = show("settings", waitFor = "ACCOUNTS") { SettingsScreen {} }
}
