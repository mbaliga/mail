@file:OptIn(ExperimentalLayoutApi::class)

package dev.aarso.mail.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.aarso.mail.ai.AiTask
import dev.aarso.mail.ai.AnthropicEngine
import dev.aarso.mail.ai.Availability
import dev.aarso.mail.ai.CloudEngineConfig
import dev.aarso.mail.ai.CloudEngineFactory
import dev.aarso.mail.ai.CloudKind
import dev.aarso.mail.ai.CloudPreset
import dev.aarso.mail.ai.CloudPresets
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.LocalBridge
import dev.aarso.mail.app.ui.components.ChoiceRow
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.ProvenanceMark
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.SwitchRow
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.list.MailBottomSheet
import dev.aarso.mail.app.ui.list.SheetRow
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.model.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Where the models are and what they may see. On-device first: Gemini Nano (if the phone has
 * AICore), or a Gemma file you put on it. Then, optionally, your own cloud key — with a list of
 * the tasks it may do, categories and accounts it never sees, redaction, and a daily budget.
 */
@Composable
fun IntelligenceSettings() {
    val app = LocalApp.current
    val g = app.graph
    val bridge = LocalBridge.current
    val scope = rememberCoroutineScope()
    val s by g.settings.collectAsState()
    val ai = s.ai
    var nanoTick by remember { mutableStateOf(0) }
    val nano by produceState<Availability?>(null, nanoTick) { value = runCatching { g.nano.availability() }.getOrNull() }
    var nanoProgress by remember { mutableStateOf<Float?>(null) }
    var copying by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CloudEngineConfig?>(null) }

    SectionLabel("Intelligence")
    SwitchRow("Use models on this phone", ai.onDeviceEnabled, { v -> g.updateSettings { it.copy(ai = it.ai.copy(onDeviceEnabled = v)) } })
    SwitchRow("Model-written summaries", ai.modelSummaries, { v -> g.updateSettings { it.copy(ai = it.ai.copy(modelSummaries = v)) } },
        subtitle = "Without a model, summaries are picked from the message itself")

    // ---- Gemini Nano ----
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ProvenanceMark(true, working = nanoProgress != null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Gemini Nano", style = Type.body.copy(fontWeight = FontWeight.Medium))
            Text(
                when (val a = nano) {
                    null -> "Checking…"
                    Availability.Ready -> "Ready on this phone"
                    is Availability.Downloadable -> "Available to download"
                    is Availability.Downloading -> "Downloading"
                    is Availability.Unavailable -> a.reason
                } + (nanoProgress?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                style = Type.small.copy(color = Palette.SheetInkDim),
            )
        }
        if (nano is Availability.Downloadable && nanoProgress == null) TextAction("Download", Palette.Navy, {
            scope.launch {
                nanoProgress = 0f
                runCatching { g.nano.download { p -> nanoProgress = p ?: nanoProgress } }.onFailure { app.say(it.message ?: "Download failed") }
                nanoProgress = null; g.intelligence.invalidate(); nanoTick++
            }
        })
    }
    Hairline()

    // ---- a local model file ----
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ProvenanceMark(true, working = copying)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Local model file", style = Type.body.copy(fontWeight = FontWeight.Medium))
            Text(s.localModelPath?.let { File(it).name } ?: "For phones without Gemini Nano: a Gemma .task file (e.g. Gemma 3 1B int4)",
                style = Type.small.copy(color = Palette.SheetInkDim))
        }
        if (s.localModelPath != null) TextAction("Remove", Palette.Danger, {
            val p = s.localModelPath
            g.localModel.close()
            g.updateSettings { it.copy(localModelPath = null) }
            p?.let { runCatching { File(it).delete() } }
        }) else TextAction(if (copying) "Copying…" else "Choose", Palette.Navy, {
            if (copying) return@TextAction
            scope.launch {
                val uri = bridge.pickDocuments(arrayOf("*/*"), multiple = false).firstOrNull() ?: return@launch
                copying = true
                val path = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = g.files.describe(uri).fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val dir = File(g.context.filesDir, "models").apply { mkdirs() }
                        val out = File(dir, name)
                        g.context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 20) } }
                        out.absolutePath
                    }.getOrNull()
                }
                copying = false
                if (path == null) app.say("Couldn't read that file") else { g.updateSettings { it.copy(localModelPath = path) }; app.say("Model ready") }
            }
        })
    }
    Hairline()

    // ---- cloud ----
    Text("Your own cloud key", style = Type.body.copy(fontWeight = FontWeight.Medium), modifier = Modifier.padding(top = 14.dp))
    Text("Optional. The key is sealed in this phone's keystore and only ever sent to the provider you chose.", style = Type.small.copy(color = Palette.SheetInkDim))
    for (c in s.cloudEngines) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProvenanceMark(false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.label, style = Type.body)
                Text(c.model.ifBlank { "default model" } + if (ai.cloudEngineId == c.id) " · in use" else "", style = Type.small.copy(color = Palette.SheetInkDim))
            }
            if (ai.cloudEngineId != c.id) TextAction("Use", Palette.Navy, { g.updateSettings { it.copy(ai = it.ai.copy(cloudEngineId = c.id)) } })
            else TextAction("Stop using", Palette.Navy, { g.updateSettings { it.copy(ai = it.ai.copy(cloudEngineId = null)) } })
            TextAction("Edit", Palette.Navy, { editing = c })
        }
    }
    TextAction("+ Add a provider", Palette.Navy, { adding = true })

    if (ai.cloudEngineId != null) {
        SwitchRow("Prefer this phone's model", ai.preferOnDevice, { v -> g.updateSettings { it.copy(ai = it.ai.copy(preferOnDevice = v)) } },
            subtitle = "Cloud only when no on-device model can do the task")
        Text("The cloud model may help with", style = Type.body, modifier = Modifier.padding(top = 8.dp))
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in AiTask.entries) SheetChip(t.label, t in ai.cloudTasks, {
                g.updateSettings { st -> st.copy(ai = st.ai.copy(cloudTasks = if (t in st.ai.cloudTasks) st.ai.cloudTasks - t else st.ai.cloudTasks + t)) }
            })
        }
        Text("Never sent to the cloud", style = Type.body, modifier = Modifier.padding(top = 8.dp))
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in Category.entries) SheetChip(c.label, c in ai.neverCloudCategories, {
                g.updateSettings { st -> st.copy(ai = st.ai.copy(neverCloudCategories = if (c in st.ai.neverCloudCategories) st.ai.neverCloudCategories - c else st.ai.neverCloudCategories + c)) }
            })
            for (a in g.accountsNow()) SheetChip(a.email, a.id in ai.neverCloudAccounts, {
                g.updateSettings { st -> st.copy(ai = st.ai.copy(neverCloudAccounts = if (a.id in st.ai.neverCloudAccounts) st.ai.neverCloudAccounts - a.id else st.ai.neverCloudAccounts + a.id)) }
            })
        }
        SwitchRow("Hide addresses, numbers and links first", ai.redactBeforeCloud, { v -> g.updateSettings { it.copy(ai = it.ai.copy(redactBeforeCloud = v)) } },
            subtitle = "Replaced with placeholders before sending and put back in the answer")
        ChoiceRow("Daily limit", listOf(50_000L, 300_000L, 1_000_000L, 0L), ai.dailyCloudTokens, { if (it == 0L) "None" else "${it / 1000}k tokens" }, { v ->
            g.updateSettings { it.copy(ai = it.ai.copy(dailyCloudTokens = v)) }
        })
        Text("Used today: ${"%,d".format(g.cloudTokensToday())} tokens", style = Type.small.copy(color = Palette.SheetInkDim))
    }

    if (adding) MailBottomSheet({ adding = false }) {
        Text("Add a provider", style = Type.heading)
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(CloudPresets.all, key = { it.id }) { p ->
                SheetRow(p.name, {
                    adding = false
                    editing = CloudEngineConfig(id = "cloud." + UUID.randomUUID().toString().take(8), presetId = p.id, kind = p.kind, label = p.name, model = p.defaultModel, baseUrl = p.baseUrl, keyRef = "ai.key." + UUID.randomUUID())
                })
            }
        }
    }
    editing?.let { c -> EngineEditor(c, CloudPresets.byId(c.presetId), onDismiss = { editing = null }) }
}

@Composable
private fun EngineEditor(initial: CloudEngineConfig, preset: CloudPreset?, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val bridge = LocalBridge.current
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf(initial) }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val exists = g.settings.value.cloudEngines.any { it.id == c.id }

    MailBottomSheet(onDismiss) {
        Text(c.label, style = Type.heading)
        if (c.kind == CloudKind.ANTHROPIC) Text(
            "Default model ${AnthropicEngine.DEFAULT_MODEL}. Requests ask for the Claude API's server-side fallback, so a refused answer is retried on a fallback model instead of failing. Pick a smaller model below for cheaper bulk work.",
            style = Type.small.copy(color = Palette.SheetInkDim), modifier = Modifier.padding(top = 4.dp),
        )
        if (preset?.needsKey != false) {
            LineField(key, { key = it }, if (exists) "New API key (leave empty to keep the current one)" else "API key",
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            preset?.keyUrl?.let { url -> TextAction("Get a key", Palette.Navy, { bridge.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) }
        }
        if (c.kind != CloudKind.ANTHROPIC || c.baseUrl.isNotBlank()) LineField(c.baseUrl, { c = c.copy(baseUrl = it) }, "Server address", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        LineField(c.model, { c = c.copy(model = it) }, "Model (empty = provider default)", trailing = {
            TextAction(if (checking) "…" else "List", Palette.Navy, {
                scope.launch {
                    checking = true
                    if (key.isNotBlank()) g.putApiKey(c, key)
                    val engine = CloudEngineFactory.create(c, key.ifBlank { null } ?: g.secrets.get(dev.aarso.mail.model.SecretRef(c.keyRef)))
                    models = runCatching { CloudEngineFactory.listModels(engine) }.getOrElse { status = it.message; emptyList() }
                    checking = false
                }
            })
        })
        if (models.isNotEmpty()) FlowRow(Modifier.padding(vertical = 8.dp).heightIn(max = 220.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, name) in models.take(40)) SheetChip(name.ifBlank { id }, c.model == id, { c = c.copy(model = id) })
        }
        status?.let { Text(it, style = Type.small.copy(color = Palette.Danger), modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SolidButton("Save", {
                scope.launch {
                    if (key.isNotBlank()) g.putApiKey(c, key.trim())
                    g.updateSettings { st ->
                        val list = st.cloudEngines.filter { it.id != c.id } + c
                        st.copy(cloudEngines = list, ai = if (st.ai.cloudEngineId == null) st.ai.copy(cloudEngineId = c.id) else st.ai)
                    }
                    onDismiss()
                }
            }, enabled = exists || key.isNotBlank() || preset?.needsKey == false)
            if (exists) TextAction("Remove", Palette.Danger, {
                scope.launch {
                    g.forgetApiKey(c)
                    g.updateSettings { st -> st.copy(cloudEngines = st.cloudEngines.filter { it.id != c.id }, ai = if (st.ai.cloudEngineId == c.id) st.ai.copy(cloudEngineId = null) else st.ai) }
                    onDismiss()
                }
            })
        }
    }
}
