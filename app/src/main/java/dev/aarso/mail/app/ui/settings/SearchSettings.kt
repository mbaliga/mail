package dev.aarso.mail.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The search index: whether it is current, and the maintenance that keeps it healthy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchSettings() {
    val app = LocalApp.current
    val g = app.graph
    val scope = rememberCoroutineScope()
    val indexing by g.indexing.collectAsState()
    var tick by remember { mutableStateOf(0) }
    var report by remember { mutableStateOf<String?>(null) }
    val status by produceState<dev.aarso.mail.store.MailStore.IndexStatus?>(null, indexing, tick) {
        value = runCatching { g.store.indexStatus() }.getOrNull()
        if (indexing) { delay(1_000); tick++ }
    }

    SectionLabel("Search")
    val st = status
    Text(
        when {
            st == null -> "Reading the search index…"
            st.rebuilding || indexing -> "Updating the search index… ${if (st.messages > 0) (100 * st.indexed.coerceAtMost(st.messages) / st.messages) else 0}% · search works meanwhile"
            else -> listOfNotNull("Up to date", "${"%,d".format(st.messages)} messages", st.version.takeIf { it.isNotBlank() }).joinToString(" · ")
        },
        style = Type.small.copy(color = Palette.SheetInkDim),
    )
    report?.let { Text(it, style = Type.small, modifier = Modifier.padding(top = 4.dp)) }
    FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetChip("Check", false, { scope.launch { report = g.store.checkIndex()?.let { "Problem: $it" } ?: "The index is sound."; tick++ } })
        SheetChip("Rebuild", false, { scope.launch { g.store.rebuildIndex(); g.startIndexing(); report = "Rebuilding in the background." } })
        SheetChip("Compact", false, { scope.launch { runCatching { g.store.optimizeIndex() }; report = "Compacted."; tick++ } })
    }
    Text(
        "Mail in Chinese, Japanese, Thai, Hindi and other scripts without spaces is indexed word by word using the phone's own language data. If search misses something, Rebuild fixes the index in place; nothing is re-downloaded.",
        style = Type.small.copy(color = Palette.SheetInkDim),
    )
}
