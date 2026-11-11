package dev.aarso.mail.app.ui.find

import dev.aarso.search.Diagnostic

/** The parser's notes, as a sentence beside the search box. */
fun Diagnostic.explain(): String = when (this) {
    is Diagnostic.UnknownField -> "“$typed:” isn't a field" + (suggestion?.let { " — did you mean “$it:”?" } ?: "")
    is Diagnostic.UnindexedFacet -> "Nothing has $key:$value"
    is Diagnostic.UnterminatedQuote -> "A quote isn't closed"
    is Diagnostic.UnterminatedRegex -> "A /pattern/ isn't closed"
    is Diagnostic.InvalidDate -> "“$raw” isn't a date $key: understands (try today, 2024-03-01, 7d, last-month)"
    is Diagnostic.InvalidNumber -> "“$raw” isn't a number for $key:"
    is Diagnostic.SemanticUnavailable -> "“$text” needs a meaning search, which isn't set up"
}

/** The grammar, as the help sheet shows it. */
val QUERY_HELP: List<Pair<String, String>> = listOf(
    "invoice" to "the word anywhere: sender, subject, body, attachment names",
    "\"exact phrase\"" to "those words in that order",
    "-word  -from:x" to "without it",
    "a OR b" to "either",
    "(a b) OR c" to "group",
    "/inv(oice)?-\\d+/" to "a pattern",
    "from:  to:  cc:  bcc:" to "people, by name or address",
    "domain:example.com" to "anyone at a domain",
    "subject:  body:  filename:  ext:pdf" to "one part of the message",
    "in:inbox|sent|archive|trash|spam|anywhere" to "where it is",
    "label:Receipts  account:work" to "a label or folder; one account",
    "is:unread|starred|replied|unanswered|pinned|snoozed|muted|person|bulk|mine" to "its state",
    "has:attachment|image|pdf|calendar|unsubscribe|tracker|otp" to "what it has",
    "category:replies|tickets|otp|travel|receipts|shipping|…" to "its tab",
    "date:today|yesterday|this-week|last-month|2024|march" to "when",
    "after:2024-03-01  before:2024-04-01" to "between",
    "older_than:30d  newer_than:2w" to "relative age (d, w, m, y)",
    "size:>5mb  larger:1mb  smaller:100kb" to "how big",
    "attachments:>2  recipients:>10" to "how many",
    "lang:de  priority:high  thread:…" to "language, importance, one conversation",
)
