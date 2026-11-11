package dev.aarso.mail.app.platform

import dev.aarso.mail.app.AppGraph
import dev.aarso.mail.app.Drafts
import dev.aarso.mail.model.EmailAddress
import dev.aarso.mail.model.MessageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/**
 * Leaving a mailing list properly: RFC 8058 one-click POST where the sender supports it, the
 * list's mailto: address otherwise; a web page is handed back for the person to open themselves
 * (we never "click" a link on someone's behalf — that is how unsubscribe phishing works).
 */
class Unsubscriber(private val graph: AppGraph) {

    sealed interface Result {
        data object Done : Result
        data class OpenPage(val url: String) : Result
        data object NotOffered : Result
    }

    private fun targets(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim().removePrefix("<").removeSuffix(">").trim() }.filter { it.isNotEmpty() }

    suspend fun unsubscribe(h: MessageHeader): Result {
        val list = targets(h.listUnsubscribe)
        val https = list.firstOrNull { it.startsWith("https://", true) }
        val mailto = list.firstOrNull { it.startsWith("mailto:", true) }
        if (https != null && h.listUnsubscribePost) {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(https).post(FormBody.Builder().add("List-Unsubscribe", "One-Click").build()).build()
                    graph.http.newCall(req).execute().use { it.isSuccessful }
                }.getOrDefault(false)
            }
            if (ok) return Result.Done
        }
        if (mailto != null) {
            val account = graph.store.account(h.accountId) ?: return Result.NotOffered
            val d = Drafts.fromMailto(account, mailto)
            val draft = d.copy(
                from = Drafts.replyFrom(account, h),
                subject = d.subject.ifBlank { "unsubscribe" },
                textBody = d.textBody.ifBlank { "unsubscribe" },
                to = d.to.ifEmpty { listOf(EmailAddress(mailto.removePrefix("mailto:").substringBefore('?'))) },
            )
            graph.send(draft, undoSeconds = 0)
            return Result.Done
        }
        if (https != null) return Result.OpenPage(https)
        list.firstOrNull { it.startsWith("http://", true) }?.let { return Result.OpenPage(it) }
        return Result.NotOffered
    }
}
