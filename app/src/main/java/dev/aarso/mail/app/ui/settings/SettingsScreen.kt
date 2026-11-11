@file:OptIn(ExperimentalLayoutApi::class)

package dev.aarso.mail.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aarso.feedback.FeedbackDraft
import dev.aarso.feedback.FeedbackFact
import dev.aarso.feedback.FeedbackOptIn
import dev.aarso.feedback.FeedbackShare
import dev.aarso.mail.app.BuildConfig
import dev.aarso.mail.app.SwipeAction
import dev.aarso.mail.app.platform.Work
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.LocalBridge
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.ChoiceRow
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.Page
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.SwitchRow
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.components.shortWhen
import dev.aarso.mail.app.ui.list.ConfirmDialog
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.digest.DigestBuilder
import dev.aarso.mail.digest.DigestWindow
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.NotifyLevel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val s by g.settings.collectAsState()
    val accounts by g.accounts.collectAsState()
    val syncing by g.sync.state.collectAsState()
    val saved by remember { g.store.observeSavedSearches() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val bridge = LocalBridge.current
    var editing by remember { mutableStateOf<Account?>(null) }
    var removing by remember { mutableStateOf<Account?>(null) }
    val feedback = remember { FeedbackOptIn(g.context) }
    var feedbackOn by remember { mutableStateOf(feedback.enabled()) }
    var feedbackText by remember { mutableStateOf("") }

    Page("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            // ---- accounts ----
            SectionLabel("Accounts")
            for (a in accounts) {
                val st = syncing[a.id]
                Row(Modifier.fillMaxWidth().clickable { editing = if (editing?.id == a.id) null else a }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.email, style = Type.body.copy(fontWeight = FontWeight.Medium))
                        Text(
                            listOfNotNull(
                                DigestBuilder.accountName(a, accounts), a.protocol.name,
                                when {
                                    st?.needsReauth == true -> "needs you to sign in again"
                                    st?.lastError != null -> "last try: ${st.lastError}"
                                    (st?.lastSuccessAt ?: 0) > 0 -> "up to date ${shortWhen(st!!.lastSuccessAt)}"
                                    else -> null
                                },
                                if (!a.enabled) "paused" else null,
                            ).joinToString(" · "),
                            style = Type.small.copy(color = if (st?.needsReauth == true || st?.lastError != null) Palette.Danger else Palette.SheetInkDim),
                        )
                    }
                    if (st?.needsReauth == true) TextAction("Sign in", Palette.Navy, { app.open(Screen.AccountSetup(a.id)) })
                }
                if (editing?.id == a.id) AccountEditor(a, onRemove = { removing = a })
                Hairline()
            }
            TextAction("+ Add an account", Palette.Navy, { app.open(Screen.AccountSetup()) })

            // ---- home ----
            SectionLabel("Home")
            ChoiceRow("New mail since", DigestWindow.entries, s.digestWindow, { it.phrase }, { w -> g.updateSettings { it.copy(digestWindow = w) } })
            SwitchRow("One-line summaries", s.tldr, { v -> g.updateSettings { it.copy(tldr = v) } }, subtitle = "Who wants what, instead of sender and subject")
            if (saved.isNotEmpty()) {
                Text("Your views as home tabs", style = Type.body, modifier = Modifier.padding(top = 8.dp))
                FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in saved) SheetChip(v.name, v.showAsTab, { scope.launch { g.store.saveSearch(v.copy(showAsTab = !v.showAsTab)) } })
                }
            }

            // ---- lists ----
            SectionLabel("Lists")
            ChoiceRow("Swipe right", SwipeAction.entries, s.swipeRight, { it.label }, { v -> g.updateSettings { it.copy(swipeRight = v) } })
            ChoiceRow("Swipe left", SwipeAction.entries, s.swipeLeft, { it.label }, { v -> g.updateSettings { it.copy(swipeLeft = v) } })
            SwitchRow("Group conversations", s.conversationView, { v -> g.updateSettings { it.copy(conversationView = v) } })

            // ---- reading ----
            SectionLabel("Reading")
            SwitchRow("Load remote images", s.loadRemoteImages, { v -> g.updateSettings { it.copy(loadRemoteImages = v) } }, subtitle = "Tracking pixels are always removed; this decides whether other images load without a tap")

            // ---- sending ----
            SectionLabel("Sending")
            ChoiceRow("Undo send window", listOf(0, 5, 10, 20, 30), s.undoSendSeconds, { if (it == 0) "Off" else "$it s" }, { v -> g.updateSettings { it.copy(undoSendSeconds = v) } })

            // ---- notifications ----
            SectionLabel("Notifications")
            Text("Each tab decides how it reaches you. Rules can override this for any message.", style = Type.small.copy(color = Palette.SheetInkDim))
            for (c in Category.entries) {
                val level = s.notify[c] ?: NotifyLevel.SILENT
                ChoiceRow(c.label, NotifyLevel.entries, level, { when (it) { NotifyLevel.URGENT -> "Urgent"; NotifyLevel.NORMAL -> "Normal"; NotifyLevel.SILENT -> "Silent"; NotifyLevel.NEVER -> "Never" } }, { v ->
                    g.updateSettings { it.copy(notify = it.notify + (c to v)) }
                })
            }
            SwitchRow("Quiet hours", s.quietHours, { v -> g.updateSettings { it.copy(quietHours = v) } }, subtitle = "Only codes, replies and security alerts make a sound")
            if (s.quietHours) {
                ChoiceRow("From", listOf(20, 21, 22, 23, 0).map { it * 60 }, s.quietFrom, { "%02d:00".format(it / 60) }, { v -> g.updateSettings { it.copy(quietFrom = v) } })
                ChoiceRow("Until", listOf(5, 6, 7, 8, 9).map { it * 60 }, s.quietTo, { "%02d:00".format(it / 60) }, { v -> g.updateSettings { it.copy(quietTo = v) } })
            }

            // ---- sync ----
            SectionLabel("Staying up to date")
            SwitchRow("Instant mail", s.alwaysOnPush, { v -> g.updateSettings { it.copy(alwaysOnPush = v) } }, subtitle = "Holds a connection open (IMAP IDLE / JMAP push) with a quiet notification. Off: checks on a schedule.")
            ChoiceRow("Check every", listOf(15, 30, 60, 180), s.pollMinutes, { if (it < 60) "$it min" else "${it / 60} h" }, { v -> g.updateSettings { it.copy(pollMinutes = v) } })
            TextAction("Check now", Palette.Navy, { Work.syncNow(g.context); app.say("Checking") })

            // ---- intelligence ----
            IntelligenceSettings()

            // ---- feedback ----
            SectionLabel("Feedback")
            SwitchRow("Ask me about new features", feedbackOn, { v -> feedback.setEnabled(v); feedbackOn = v; g.updateSettings { it.copy(feedbackOptIn = v) } },
                subtitle = "Nothing is ever sent by itself: you see the whole message and send it from your own mail.")
            if (feedbackOn) {
                LineField(feedbackText, { feedbackText = it }, "What should be better?", singleLine = false)
                SolidButton("Write it up", {
                    val draft = FeedbackDraft(
                        appName = "You've got Mail", appVersion = BuildConfig.VERSION_NAME, feature = "General", message = feedbackText,
                        facts = listOf(FeedbackFact("Accounts", accounts.size.toString()), FeedbackFact("Android", android.os.Build.VERSION.RELEASE)),
                    )
                    bridge.launch(FeedbackShare.chooser(draft).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }, Modifier.padding(top = 10.dp), enabled = feedbackText.isNotBlank())
            }

            // ---- about ----
            SectionLabel("About")
            Text("You've got Mail ${BuildConfig.VERSION_NAME}", style = Type.body)
            Text("IMAP, POP3, JMAP and SMTP, written for this app. Your mail and keys stay on this phone; cloud models see only what you allow.", style = Type.small.copy(color = Palette.SheetInkDim))
            Text("Typeface: Hyle Grotesk Classic (SIL Open Font License).", style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 4.dp))
            if (BuildConfig.DEBUG) TextAction("Preview the crash screen", Palette.Navy, {
                bridge.launch(dev.aarso.crashrecovery.CrashRecovery.previewIntent(g.context, "You've got Mail").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            Spacer(Modifier.height(40.dp))
        }
    }

    removing?.let { a ->
        ConfirmDialog("Remove ${a.email}?", "Its mail is deleted from this phone (not from the server), and its saved password is forgotten.", "Remove", { removing = null }) {
            removing = null
            editing = null
            scope.launch {
                g.secrets.delete(a.secret)
                a.outgoingSecret?.let { g.secrets.delete(it) }
                g.store.deleteAccount(a.id)
            }
        }
    }
}

@Composable
private fun AccountEditor(a: Account, onRemove: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val scope = rememberCoroutineScope()
    var name by remember(a.id) { mutableStateOf(a.displayName) }
    var signature by remember(a.id) { mutableStateOf(a.primaryIdentity.signature) }
    var alias by remember { mutableStateOf("") }
    fun save(change: (Account) -> Account) = scope.launch { g.store.saveAccount(change(a)) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        LineField(name, { name = it }, "Name people see", trailing = { if (name != a.displayName) TextAction("Save", Palette.Navy, { save { it.copy(displayName = name) } }) })
        LineField(signature, { signature = it }, "Signature", singleLine = false, trailing = {
            if (signature != a.primaryIdentity.signature) TextAction("Save", Palette.Navy, {
                save { acc ->
                    val ids = acc.identities.ifEmpty { listOf(acc.primaryIdentity) }
                    acc.copy(identities = ids.map { if (it.isDefault || ids.size == 1) it.copy(signature = signature) else it })
                }
            })
        })
        Text("Send as", style = Type.body, modifier = Modifier.padding(top = 10.dp))
        for (id in a.identities) Text(id.address.address + if (id.isDefault) " (default)" else "", style = Type.small.copy(color = Palette.SheetInkDim))
        LineField(alias, { alias = it }, "Add an alias address", trailing = {
            if (alias.contains('@')) TextAction("Add", Palette.Navy, {
                save { acc -> acc.copy(identities = acc.identities.ifEmpty { listOf(acc.primaryIdentity) } + dev.aarso.mail.model.Identity(dev.aarso.mail.model.EmailAddress(alias.trim(), acc.displayName))) }
                alias = ""
            })
        })
        SwitchRow("Sync this account", a.enabled, { v -> save { it.copy(enabled = v) } })
        SwitchRow("Keep a connection open when instant mail is on", a.sync.push, { v -> save { it.copy(sync = it.sync.copy(push = v)) } })
        SwitchRow("Download messages for offline", a.sync.prefetchBodies, { v -> save { it.copy(sync = it.sync.copy(prefetchBodies = v)) } }, subtitle = "Summaries, codes and search work best with this on")
        ChoiceRow("Keep on this phone", listOf(30, 90, 365, 3650), a.sync.windowDays, { if (it >= 3650) "Everything" else if (it >= 365) "1 year" else "$it days" }, { v -> save { it.copy(sync = it.sync.copy(windowDays = v)) } })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextAction("Sign in again", Palette.Navy, { app.open(Screen.AccountSetup(a.id)) })
            TextAction("Sync now", Palette.Navy, { Work.syncNow(g.context, a.id) })
            TextAction("Remove", Palette.Danger, onRemove)
        }
    }
}
