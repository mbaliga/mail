package dev.aarso.mail.app

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.mail.app.platform.PushService
import dev.aarso.mail.app.ui.ActivityBridge
import dev.aarso.mail.app.ui.AppRoot
import dev.aarso.mail.app.ui.AppState
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.LocalBridge
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.theme.MailTheme
import dev.aarso.mail.model.DraftAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), ActivityBridge {

    private val graph get() = (application as MailApp).graph
    private lateinit var state: AppState

    // ---- activity results, as suspend calls for Compose code ----
    private var pendingResult: CompletableDeferred<ActivityResult>? = null
    private var pendingDocs: CompletableDeferred<List<Uri>>? = null
    private var pendingCreate: CompletableDeferred<Uri?>? = null

    private val forResult: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { pendingResult?.complete(it) }
    private val forSender: ActivityResultLauncher<IntentSenderRequest> = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { pendingResult?.complete(it) }
    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { pendingDocs?.complete(listOfNotNull(it)) }
    private val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { pendingDocs?.complete(it) }
    // Create-document with the real MIME type per call: some pickers refuse a wildcard type.
    private val createDoc = registerForActivityResult(object : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>) = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == RESULT_OK }
    }) { pendingCreate?.complete(it) }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override val activity get() = this

    override suspend fun startForResult(intent: Intent): ActivityResult {
        val d = CompletableDeferred<ActivityResult>().also { pendingResult = it }
        forResult.launch(intent)
        return d.await()
    }

    override suspend fun startIntentSender(pending: PendingIntent): ActivityResult {
        val d = CompletableDeferred<ActivityResult>().also { pendingResult = it }
        forSender.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        return d.await()
    }

    override suspend fun pickDocuments(types: Array<String>, multiple: Boolean): List<Uri> {
        val d = CompletableDeferred<List<Uri>>().also { pendingDocs = it }
        if (multiple) openMany.launch(types) else openOne.launch(types)
        return d.await()
    }

    override suspend fun createDocument(name: String, mime: String): Uri? {
        val d = CompletableDeferred<Uri?>().also { pendingCreate = it }
        createDoc.launch(name to mime)
        return d.await()
    }

    override fun requestNotifications() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun launch(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { state.say("No app can open that") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashRecovery.maybeShowRecovery(this, getString(R.string.app_name))) return
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        state = AppState(graph, graph.scope)
        setContent {
            val bridge = remember { this@MainActivity as ActivityBridge }
            MailTheme {
                CompositionLocalProvider(LocalApp provides state, LocalBridge provides bridge) {
                    AppRoot(state)
                }
            }
        }
        handle(intent)
        // A background Google token refresh that needs the person surfaces here.
        lifecycleScope.launch {
            graph.tokens.consents.collect { c ->
                if (c is dev.aarso.mail.app.platform.ConsentNeeded.Google) {
                    val r = startIntentSender(c.pendingIntent)
                    if (graph.tokens.completeGoogleConsent(c.accountEmail, r.data) != null) graph.scope.launch { graph.sync.syncAll(inboxOnly = true) }
                }
            }
        }
        if (graph.accountsNow().isNotEmpty()) requestNotifications()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!::state.isInitialized) return
        // Opening the app is the best moment to catch up: quick inbox sync in-process, push if wanted.
        val last = graph.sync.state.value.values.maxOfOrNull { it.lastSuccessAt } ?: 0
        if (System.currentTimeMillis() - last > 60_000) graph.scope.launch { graph.sync.syncAll(inboxOnly = true) }
        if (graph.settings.value.alwaysOnPush) PushService.sync(this, true)
    }

    override fun onStop() {
        super.onStop()
        if (!::state.isInitialized) return
        // "since you last looked" means since the app last left the screen.
        graph.updateSettings { it.copy(lastLookAt = System.currentTimeMillis()) }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (!::state.isInitialized) return
        when (intent.action) {
            ACTION_OPEN_MESSAGE -> intent.getLongExtra(EXTRA_MESSAGE_ID, -1).takeIf { it > 0 }?.let { state.open(Screen.Reader(it)) }
            ACTION_OPEN_OUTBOX -> state.open(Screen.Outbox)
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "mailto") {
                    val a = graph.accountsNow().firstOrNull() ?: return state.open(Screen.AccountSetup())
                    state.compose(Drafts.fromMailto(a, data.toString()))
                } else if (intent.type == "message/rfc822" || data.toString().endsWith(".eml", true)) {
                    lifecycleScope.launch {
                        val bytes = graph.files.readUri(data) ?: return@launch state.say("Couldn't open that file")
                        state.open(Screen.External(bytes, data.lastPathSegment ?: "message.eml"))
                    }
                }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val a = graph.accountsNow().firstOrNull() ?: return state.open(Screen.AccountSetup())
                val streams: List<Uri> = if (intent.action == Intent.ACTION_SEND) {
                    listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                } else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                val atts: List<DraftAttachment> = streams.mapNotNull { runCatching { graph.files.describe(it) }.getOrNull() }
                val to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL).orEmpty().map { dev.aarso.mail.model.EmailAddress(it) }
                val d = Drafts.new(a, to = to, subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(), body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                state.compose(d.copy(attachments = atts, cc = intent.getStringArrayExtra(Intent.EXTRA_CC).orEmpty().map { dev.aarso.mail.model.EmailAddress(it) }))
            }
        }
    }

    companion object {
        const val ACTION_OPEN_MESSAGE = "dev.aarso.mail.OPEN_MESSAGE"
        const val ACTION_OPEN_OUTBOX = "dev.aarso.mail.OPEN_OUTBOX"
        const val EXTRA_MESSAGE_ID = "message_id"
    }
}
