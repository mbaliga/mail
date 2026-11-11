package dev.aarso.mail.digest

import dev.aarso.mail.discovery.Providers
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.Category
import dev.aarso.mail.model.SavedSearch
import dev.aarso.mail.search.MailSearch
import dev.aarso.mail.search.SearchScope
import dev.aarso.mail.sync.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The "since …" of "You've got N new mail since …". */
enum class DigestWindow(val phrase: String) {
    SINCE_LAST_LOOK("since you last looked"),
    TODAY("today"),
    SINCE_YESTERDAY("since yesterday"),
    THIS_WEEK("this week"),
    LAST_7_DAYS("in the last 7 days"),
    THIS_MONTH("this month"),
    LAST_30_DAYS("in the last 30 days"),
}

/** One tab under the headline — a category, a saved view, or everything. */
data class DigestTab(
    val id: String,
    val label: String,
    /** The list query this tab shows, in the shared grammar (so it is editable and saveable). */
    val query: String,
    val count: Int,
    val unread: Int,
    val category: Category? = null,
)

data class Digest(
    val total: Int,
    val unread: Int,
    val windowStart: Long,
    val tabs: List<DigestTab>,
)

/**
 * The home screen's arithmetic: how much arrived in the window, in which inboxes, sorted into
 * which tabs. "New" means *arrived in the window and still in the inbox*: archiving a message takes
 * it off the count, so the number is a to-do list that shrinks, not a statistic.
 */
class DigestBuilder(
    private val search: MailSearch,
    private val clock: Clock = Clock.SYSTEM,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun windowStart(window: DigestWindow, lastLookAt: Long): Long {
        val now = clock.now()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (window) {
            DigestWindow.SINCE_LAST_LOOK -> if (lastLookAt > 0) lastLookAt else start(today.minusDays(1))
            DigestWindow.TODAY -> start(today)
            DigestWindow.SINCE_YESTERDAY -> start(today.minusDays(1))
            DigestWindow.THIS_WEEK -> start(today.with(DayOfWeek.MONDAY))
            DigestWindow.LAST_7_DAYS -> now - 7 * DAY
            DigestWindow.THIS_MONTH -> start(today.withDayOfMonth(1))
            DigestWindow.LAST_30_DAYS -> now - 30 * DAY
        }
    }

    fun baseQuery(windowStart: Long): String = "in:inbox -is:mine ts:>=$windowStart"

    suspend fun build(accountIds: Set<Long>, window: DigestWindow, lastLookAt: Long, savedTabs: List<SavedSearch> = emptyList()): Digest {
        val start = windowStart(window, lastLookAt)
        val base = baseQuery(start)
        val scope = SearchScope(accountIds = accountIds)
        val byCategory = search.groupCounts(search.parse(base, natural = false), scope, "m.category")
        val tabs = ArrayList<DigestTab>()
        var total = 0
        var unread = 0
        for (c in Category.entries) {
            val (n, u) = byCategory[c.key] ?: continue
            if (n == 0) continue
            total += n; unread += u
            tabs += DigestTab("cat:${c.key}", c.label.uppercase(), "$base category:${c.key}", n, u, c)
        }
        for (s in savedTabs.filter { it.showAsTab }) {
            val q = "$base (${s.query})"
            val n = search.count(search.parse(q, natural = false), scope)
            if (n > 0) tabs += DigestTab("saved:${s.id}", s.name.uppercase(), q, n, 0)
        }
        if (tabs.size > 1) tabs += DigestTab("all", "EVERYTHING", base, total, unread)
        return Digest(total, unread, start, tabs)
    }

    companion object {
        private const val DAY = 86_400_000L

        /** "on Gmail", "on iCloud", "on Work", "everywhere" — the headline's account phrase. */
        fun accountPhrase(selected: List<Account>, all: List<Account>): String = when {
            selected.isEmpty() || selected.size == all.size && all.size > 1 -> "everywhere"
            selected.size == 1 -> "on " + accountName(selected.first(), all)
            else -> "in ${selected.size} inboxes"
        }

        /** The provider's name, unless two accounts share a provider — then the person's own label. */
        fun accountName(a: Account, all: List<Account>): String {
            val provider = Providers.byId(a.providerId)?.name
            val clash = all.count { it.providerId == a.providerId } > 1
            return when {
                provider != null && !clash -> provider
                a.displayName.isNotBlank() && !a.displayName.equals(a.email, true) && clash -> a.displayName
                else -> a.email
            }
        }
    }
}
