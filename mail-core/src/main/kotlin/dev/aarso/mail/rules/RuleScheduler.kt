package dev.aarso.mail.rules

import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.RuleTrigger

/** Which scheduled rules are due. Pure: the app's periodic worker asks, then runs them. */
object RuleScheduler {

    /** Enabled rules with a schedule whose interval has elapsed since they last ran, in rule order. */
    fun due(rules: List<MailRule>, now: Long): List<MailRule> =
        rules.filter { it.enabled && RuleTrigger.SCHEDULED in it.triggers && now - it.lastRunAt >= it.intervalMinutes * 60_000L }
            .sortedWith(compareBy({ it.order }, { it.id }))

    /** When [rule] is next due (epoch millis), or null if it has no schedule. A rule that never ran is due now. */
    fun nextRunAt(rule: MailRule, now: Long): Long? =
        if (RuleTrigger.SCHEDULED !in rule.triggers) null else if (rule.lastRunAt == 0L) now else rule.lastRunAt + rule.intervalMinutes * 60_000L

    /** The shortest interval among enabled scheduled rules — how often the periodic worker needs to wake (null: never). */
    fun wakeEveryMinutes(rules: List<MailRule>): Int? =
        rules.filter { it.enabled && RuleTrigger.SCHEDULED in it.triggers }.minOfOrNull { it.intervalMinutes }
}
