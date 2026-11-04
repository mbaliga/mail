package dev.aarso.mail.app.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aarso.cellshell.WheelItem
import dev.aarso.cellshell.WordWheelRail
import dev.aarso.mail.app.ui.LocalApp
import dev.aarso.mail.app.ui.Screen
import dev.aarso.mail.app.ui.components.HyIcon
import dev.aarso.mail.app.ui.components.HyIcons
import dev.aarso.mail.app.ui.components.IconAction
import dev.aarso.mail.app.ui.components.Room
import dev.aarso.mail.app.ui.components.RoomChip
import dev.aarso.mail.app.ui.theme.Palette
import dev.aarso.mail.app.ui.theme.Type
import dev.aarso.mail.digest.DigestBuilder
import dev.aarso.mail.model.Mailbox
import dev.aarso.mail.model.MailboxRole

private data class Place(val id: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val screen: Screen, val unread: Int = 0)

/**
 * The left room: every place mail can be, as Hyle's word wheel. Turn to a place, tap it (or its
 * arrow) to open. The unified places come first; each account's own folders and labels follow,
 * then saved views.
 */
@Composable
fun PlacesRoom(onOpen: (Screen) -> Unit) {
    val app = LocalApp.current
    val g = app.graph
    val accounts by g.accounts.collectAsState()
    val boxes by remember { g.store.observeMailboxes() }.collectAsState(initial = emptyList())
    val saved by remember { g.store.observeSavedSearches() }.collectAsState(initial = emptyList())
    val outbox by remember { g.store.observeOutbox() }.collectAsState(initial = emptyList())
    var account by rememberSaveable { mutableStateOf<Long?>(null) }

    val scoped = account?.let { setOf(it) } ?: emptySet()
    fun unread(role: MailboxRole) = boxes.filter { it.role == role && (account == null || it.accountId == account) }.sumOf { it.unreadCount }

    val places = remember(boxes, saved, outbox, account, accounts) {
        buildList {
            add(Place("inbox", "Inbox", HyIcons.inbox, Screen.Mailbox("Inbox", "in:inbox", scoped), unread(MailboxRole.INBOX)))
            add(Place("starred", "Starred", HyIcons.star, Screen.Mailbox("Starred", "is:starred", scoped)))
            add(Place("snoozed", "Snoozed", HyIcons.snooze, Screen.Mailbox("Snoozed", "is:snoozed", scoped)))
            add(Place("sent", "Sent", HyIcons.sent, Screen.Mailbox("Sent", "in:sent", scoped)))
            add(Place("drafts", "Drafts", HyIcons.drafts, Screen.Mailbox("Drafts", "in:drafts", scoped)))
            if (outbox.isNotEmpty()) add(Place("outbox", "Outbox", HyIcons.outbox, Screen.Outbox, outbox.size))
            add(Place("archive", "Archive", HyIcons.archive, Screen.Mailbox("Archive", "in:archive", scoped)))
            add(Place("spam", "Spam", HyIcons.shield, Screen.Mailbox("Spam", "in:spam", scoped), unread(MailboxRole.JUNK)))
            add(Place("trash", "Trash", HyIcons.trash, Screen.Mailbox("Trash", "in:trash", scoped)))
            add(Place("all", "All mail", HyIcons.all, Screen.Mailbox("All mail", "in:anywhere", scoped)))
            for (s in saved.filter { it.kind == dev.aarso.mail.model.SavedSearchKind.VIEW }) {
                add(Place("saved:${s.id}", s.name, HyIcons.search, Screen.Mailbox(s.name, s.query, scoped)))
            }
            val own: List<Mailbox> = boxes.filter { it.role == MailboxRole.NONE && it.selectable && (account == null || it.accountId == account) }
                .sortedBy { it.name.lowercase() }
            val multi = accounts.size > 1 && account == null
            for (b in own) {
                val owner = accounts.firstOrNull { it.id == b.accountId }
                val label = if (multi && owner != null) "${b.name} · ${DigestBuilder.accountName(owner, accounts)}" else b.name
                add(Place("box:${b.id}", label, HyIcons.folder, Screen.Mailbox(b.name, "label:\"${b.name}\"", setOf(b.accountId)), b.unreadCount))
            }
        }
    }
    var selected by rememberSaveable { mutableStateOf("inbox") }
    val current = places.firstOrNull { it.id == selected } ?: places.first()

    Room("Places", actions = {
        IconAction(HyIcons.settings, "Settings", Palette.RoomInk, { onOpen(Screen.Settings) })
    }) {
        if (accounts.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoomChip("Every inbox", account == null, { account = null })
                for (a in accounts) RoomChip(DigestBuilder.accountName(a, accounts), account == a.id, { account = a.id })
            }
        }
        WordWheelRail(
            items = places.map { WheelItem(it.id, it.label) },
            selectedId = current.id,
            onSelect = { id -> if (id == selected) places.firstOrNull { it.id == id }?.let { onOpen(it.screen) } else selected = id },
            inkColor = Palette.RoomInk,
            accentColor = Palette.Accent,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp),
            marker = { item -> places.firstOrNull { it.id == item.id }?.let { HyIcon(it.icon, null, Palette.Accent, size = 22.dp) } },
            trailing = { item ->
                val p = places.firstOrNull { it.id == item.id }
                Row(
                    Modifier.padding(start = 12.dp).clickable { p?.let { onOpen(it.screen) } }.semantics { contentDescription = "Open ${item.label}" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (p != null && p.unread > 0) Text("%,d".format(p.unread), style = Type.label.copy(color = Palette.Count))
                    Spacer(Modifier.width(8.dp))
                    HyIcon(HyIcons.arrowRight, null, Palette.RoomInk, size = 22.dp)
                }
            },
        )
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoomChip("Accounts", false, { onOpen(Screen.Settings) })
                RoomChip("Add an account", false, { onOpen(Screen.AccountSetup()) })
                RoomChip("Shortcuts", false, { onOpen(Screen.Shortcuts) })
            }
        }
    }
}
