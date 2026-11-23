package dev.aarso.mail.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a rule matches: a tree of conditions over one message.
 *
 * [Query] is the main leaf and it **reuses search-core's grammar and evaluator** — anything the Find box
 * can express, a rule can match, and a rule's matches are exactly what that search lists. The other
 * leaves exist because either the grammar cannot say it ([Header], [IsReply], [FromKnownContact]) or a
 * rule wants a typed field rather than a string ([AgeOver], [SizeOver], [ListId], [InCategory]).
 *
 * Overlap with the grammar, so nobody maintains two meanings:
 *  - [AgeOver] = `older_than:` (the engine pushes it down as that facet when the age is whole days).
 *  - [SizeOver] = `larger:`.
 *  - [ListId] = `list:` (a case-insensitive substring of the List-Id header).
 *  - [InCategory] = `category:`.
 *  - [IsReply] is *not* `is:replied`, which means "I answered this"; it means "this message is itself a reply".
 *  - [Header] covers only headers the store keeps (see `HeaderSource`); a header it does not keep never matches.
 *
 * (The "any of" node is named [AnyOf], not `Any`, so it cannot shadow Kotlin's `Any` inside this file.)
 */
@Serializable
sealed interface Condition {
    @Serializable @SerialName("all") data class All(val of: List<Condition>) : Condition
    @Serializable @SerialName("any") data class AnyOf(val of: List<Condition>) : Condition
    @Serializable @SerialName("not") data class Not(val of: Condition) : Condition

    /** search-core syntax; blank matches everything. */
    @Serializable @SerialName("query") data class Query(val q: String) : Condition

    @Serializable @SerialName("header") data class Header(val name: String, val op: HeaderOp = HeaderOp.CONTAINS, val value: String = "") : Condition

    /** Received more than [minutes] ago. */
    @Serializable @SerialName("age_over") data class AgeOver(val minutes: Long) : Condition
    @Serializable @SerialName("size_over") data class SizeOver(val bytes: Long) : Condition
    @Serializable @SerialName("list_id") data class ListId(val value: String) : Condition
    @Serializable @SerialName("is_reply") data object IsReply : Condition
    @Serializable @SerialName("known_contact") data object FromKnownContact : Condition
    @Serializable @SerialName("category") data class InCategory(val category: Category) : Condition
}

@Serializable
enum class HeaderOp { CONTAINS, EQUALS, REGEX, EXISTS }

/** Every leaf in the tree, in order. */
fun Condition.leaves(): List<Condition> = when (this) {
    is Condition.All -> of.flatMap { it.leaves() }
    is Condition.AnyOf -> of.flatMap { it.leaves() }
    is Condition.Not -> of.leaves()
    else -> listOf(this)
}

/** Flatten nested all/any, drop empty groups and unwrap single-child groups; the shape the engine reasons about. */
fun Condition.normalized(): Condition = when (this) {
    is Condition.All -> of.map { it.normalized() }.flatMap { if (it is Condition.All) it.of else listOf(it) }
        .let { if (it.size == 1) it[0] else Condition.All(it) }
    is Condition.AnyOf -> of.map { it.normalized() }.flatMap { if (it is Condition.AnyOf) it.of else listOf(it) }
        .let { if (it.size == 1) it[0] else Condition.AnyOf(it) }
    is Condition.Not -> of.normalized().let { if (it is Condition.Not) it.of else Condition.Not(it) }
    else -> this
}

/** A short human reading, for old surfaces that show `MailRule.query` and for the editor's summary. */
fun Condition.describe(): String = when (this) {
    is Condition.All -> if (of.isEmpty()) "" else of.joinToString(" and ") { it.describeNested() }
    is Condition.AnyOf -> of.joinToString(" or ") { it.describeNested() }
    is Condition.Not -> "not " + of.describeNested()
    is Condition.Query -> q
    is Condition.Header -> when (op) {
        HeaderOp.EXISTS -> "has header $name"
        HeaderOp.CONTAINS -> "$name contains “$value”"
        HeaderOp.EQUALS -> "$name is “$value”"
        HeaderOp.REGEX -> "$name matches /$value/"
    }
    is Condition.AgeOver -> "older than " + durationWords(minutes)
    is Condition.SizeOver -> "larger than " + sizeWords(bytes)
    is Condition.ListId -> "from list “$value”"
    Condition.IsReply -> "is a reply"
    Condition.FromKnownContact -> "from someone I know"
    is Condition.InCategory -> "in ${category.label}"
}

private fun Condition.describeNested(): String = if (this is Condition.All && of.size > 1 || this is Condition.AnyOf && of.size > 1) "(" + describe() + ")" else describe()

private fun durationWords(minutes: Long): String = when {
    minutes % (60 * 24 * 7) == 0L && minutes >= 60 * 24 * 7 -> "${minutes / (60 * 24 * 7)} week" + if (minutes / (60 * 24 * 7) == 1L) "" else "s"
    minutes % (60 * 24) == 0L && minutes >= 60 * 24 -> "${minutes / (60 * 24)} day" + if (minutes / (60 * 24) == 1L) "" else "s"
    minutes % 60 == 0L && minutes >= 60 -> "${minutes / 60} hour" + if (minutes / 60 == 1L) "" else "s"
    else -> "$minutes minutes"
}

private fun sizeWords(bytes: Long): String = when {
    bytes >= 1_000_000 && bytes % 1_000_000 == 0L -> "${bytes / 1_000_000} MB"
    bytes >= 1_000 && bytes % 1_000 == 0L -> "${bytes / 1_000} KB"
    else -> "$bytes bytes"
}
