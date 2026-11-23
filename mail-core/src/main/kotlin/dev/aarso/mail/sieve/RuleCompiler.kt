package dev.aarso.mail.sieve

import dev.aarso.mail.model.MailRule
import dev.aarso.mail.model.MailboxRole

/** What a server can do with rules: how they get there, which Sieve extensions it has, and the account's own facts the compiler needs. */
class ServerCapabilities(
    val transport: Transport,
    /** Sieve extensions the server advertises (`fileinto`, `imap4flags`, `copy`…). */
    val extensions: Set<String>,
    /** Addresses the account receives as, lower-case — for `from:me` and `to:me`. */
    val accountAddresses: Set<String> = emptySet(),
    /** The server-side folder name for a role or a name the person gave, or null if there is no such folder. */
    val folderName: (role: MailboxRole?, name: String?) -> String? = { _, name -> name },
) {
    enum class Transport { MANAGESIEVE, JMAP, NONE }
}

/** One rule, translated for a server — or the reasons it cannot be. */
sealed interface CompileResult

/**
 * [body] is the rule's `if … { … }` block; [extensions] what the script must `require`; [warnings] things that are
 * the same in practice but not in every corner (a note, not a refusal).
 */
data class Compiled(val body: String, val extensions: Set<String>, val warnings: List<String> = emptyList()) : CompileResult

/** The rule uses something the server cannot do, and compiling it anyway would make it behave differently. Each reason is one line for a person. */
data class NotPortable(val reasons: List<String>) : CompileResult

/** Turns a rule into something a server runs for the person. Sieve is the one implemented; see [GmailFilterCompiler] and [GraphRuleCompiler]. */
interface RuleCompiler {
    fun compile(rule: MailRule, capabilities: ServerCapabilities): CompileResult
}

/**
 * Gmail filters. TODO(not started): they live behind the Gmail API's settings scopes, which need their own OAuth consent and Google's
 * verification, so there is deliberately no network code here. Every rule stays on the phone for Gmail accounts.
 */
object GmailFilterCompiler : RuleCompiler {
    override fun compile(rule: MailRule, capabilities: ServerCapabilities): CompileResult =
        NotPortable(listOf("Gmail filters need extra Google permissions that aren't set up yet"))
}

/**
 * Outlook / Microsoft 365 inbox rules through Microsoft Graph. TODO(not started): needs the MailboxSettings scopes and tenant consent,
 * so there is deliberately no network code here. Every rule stays on the phone for those accounts.
 */
object GraphRuleCompiler : RuleCompiler {
    override fun compile(rule: MailRule, capabilities: ServerCapabilities): CompileResult =
        NotPortable(listOf("Outlook rules need extra Microsoft permissions that aren't set up yet"))
}
