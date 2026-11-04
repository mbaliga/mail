@file:OptIn(ExperimentalMaterial3Api::class)

package dev.aarso.mail.app.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.components.Hairline
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.LineField
import dev.aarso.mail.app.ui.components.SectionLabel
import dev.aarso.mail.app.ui.components.SheetChip
import dev.aarso.mail.app.ui.components.SolidButton
import dev.aarso.mail.app.ui.components.TextAction
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A white bottom sheet in the app's material. */
@Composable
fun MailBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Sheet,
        contentColor = Palette.SheetInk,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = { Spacer(Modifier.height(14.dp)) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp), content = content)
    }
}

@Composable
fun SheetRow(text: String, onClick: () -> Unit, icon: ImageVector? = null, detail: String? = null, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { HyIcon(icon, null, if (danger) Palette.Danger else Palette.SheetInk, size = 20.dp); Spacer(Modifier.width(16.dp)) }
        Text(text, style = Type.body.copy(color = if (danger) Palette.Danger else Palette.SheetInk), modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, style = Type.small.copy(color = Palette.SheetInkDim))
    }
}

/** Named moments, in the person's zone: later today, this evening, tomorrow, the weekend, next week. */
object Moments {
    data class Moment(val label: String, val at: Long)

    fun presets(zone: ZoneId = ZoneId.systemDefault(), now: LocalDateTime = LocalDateTime.now(zone)): List<Moment> {
        fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
        val fmt = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
        val out = ArrayList<Moment>()
        val later = now.plusHours(3).withMinute(0).withSecond(0).withNano(0)
        if (later.toLocalDate() == now.toLocalDate() && later.hour < 21) out += Moment("Later today · ${fmt.format(later)}", ms(later))
        val evening = now.toLocalDate().atTime(18, 0)
        if (now.isBefore(evening.minusHours(1))) out += Moment("This evening · ${fmt.format(evening)}", ms(evening))
        val tomorrow = now.toLocalDate().plusDays(1).atTime(8, 0)
        out += Moment("Tomorrow · ${fmt.format(tomorrow)}", ms(tomorrow))
        val sat = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).atTime(9, 0)
        if (now.dayOfWeek != DayOfWeek.SATURDAY && now.dayOfWeek != DayOfWeek.SUNDAY) out += Moment("This weekend · ${fmt.format(sat)}", ms(sat))
        val mon = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(8, 0)
        out += Moment("Next week · ${fmt.format(mon)}", ms(mon))
        val month = now.toLocalDate().plusMonths(1).atTime(8, 0)
        out += Moment("In a month · ${DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(month)}", ms(month))
        return out
    }
}

/** Pick a moment: the presets, or a date and time. */
@Composable
fun MomentSheet(title: String, onDismiss: () -> Unit, onPick: (Long) -> Unit, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    var custom by remember { mutableStateOf(false) }
    MailBottomSheet(onDismiss) {
        Text(title, style = Type.heading)
        Spacer(Modifier.height(8.dp))
        for (p in Moments.presets()) SheetRow(p.label, { onPick(p.at) })
        SheetRow("Pick a date and time…", { custom = true })
        extra?.invoke(this)
    }
    if (custom) DateTimeDialog(onDismiss = { custom = false }, onPick = { custom = false; onPick(it) })
}

@Composable
fun DateTimeDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val date = rememberDatePickerState(initialSelectedDateMillis = LocalDate.now(zone).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val time = rememberTimePickerState(initialHour = 8, initialMinute = 0)
    var step by remember { mutableStateOf(0) }
    if (step == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextAction("Next", Palette.Accent, { step = 1 }) },
            dismissButton = { TextAction("Cancel", Palette.RoomInkDim, onDismiss) },
        ) { DatePicker(date) }
    } else {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextAction("Done", Palette.Accent, {
                    val d = Instant.ofEpochMilli(date.selectedDateMillis ?: System.currentTimeMillis()).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(d.atTime(LocalTime.of(time.hour, time.minute)).atZone(zone).toInstant().toEpochMilli())
                })
            },
            dismissButton = { TextAction("Back", Palette.RoomInkDim, { step = 0 }) },
            text = { TimePicker(time) },
        )
    }
}

/** Move to a folder (or, on Gmail, a label-folder) of the account the messages are in. */
@Composable
fun MoveSheet(accountIds: Set<Long>, onDismiss: () -> Unit, onPick: (Mailbox) -> Unit) {
    val app = LocalApp.current
    val boxes by produceState(emptyList<Mailbox>(), accountIds) {
        value = app.graph.store.mailboxes().filter { (accountIds.isEmpty() || it.accountId in accountIds) && it.selectable }
    }
    var filter by remember { mutableStateOf("") }
    MailBottomSheet(onDismiss) {
        Text("Move to", style = Type.heading)
        LineField(filter, { filter = it }, "Find a folder")
        val shown = boxes.filter { filter.isBlank() || it.name.contains(filter, true) }
            .sortedWith(compareBy({ if (it.role == MailboxRole.NONE) 1 else 0 }, { it.name.lowercase() }))
        val multi = boxes.map { it.accountId }.distinct().size > 1
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(shown, key = { it.id }) { b ->
                SheetRow(b.name, { onPick(b) }, detail = if (multi) app.graph.accountsNow().firstOrNull { it.id == b.accountId }?.email else null)
            }
        }
    }
}

/** Add or remove labels (Gmail labels, JMAP keywords, IMAP keywords). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelSheet(current: Set<String>, known: List<String>, onDismiss: () -> Unit, onApply: (add: List<String>, remove: List<String>) -> Unit) {
    var chosen by remember(current) { mutableStateOf(current) }
    var fresh by remember { mutableStateOf("") }
    MailBottomSheet(onDismiss) {
        Text("Labels", style = Type.heading)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            for (l in (known + chosen).distinct().sorted()) {
                SheetChip(l, l in chosen, { chosen = if (l in chosen) chosen - l else chosen + l })
            }
        }
        LineField(fresh, { fresh = it }, "New label", trailing = {
            TextAction("Add", Palette.SheetInk, { if (fresh.isNotBlank()) { chosen = chosen + fresh.trim(); fresh = "" } })
        })
        Spacer(Modifier.height(16.dp))
        SolidButton("Apply", { onApply((chosen - current).toList(), (current - chosen).toList()) }, Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySheet(current: Category?, onDismiss: () -> Unit, onPick: (Category) -> Unit) {
    MailBottomSheet(onDismiss) {
        Text("Belongs in", style = Type.heading)
        Text("Teaches the sorter: future mail like this lands here too.", style = Type.small.copy(color = Palette.SheetInkDim))
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in Category.entries) SheetChip(c.label, c == current, { onPick(c) })
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Confirm something irreversible. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextAction(confirm, Palette.Danger, onConfirm) },
        dismissButton = { TextAction("Cancel", Palette.RoomInkDim, onDismiss) },
    )
}

@Composable
fun SheetDivider() { Hairline(Modifier.padding(vertical = 4.dp)) }

@Composable
fun SheetSection(text: String) = SectionLabel(text)

@Composable
fun LaunchOnce(block: suspend () -> Unit) = LaunchedEffect(Unit) { block() }
