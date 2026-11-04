package dev.aarso.mail.intel

import dev.aarso.mail.model.Category
import dev.aarso.mail.model.MessageHeader

/** Facts about a message that depend on the rest of the mailbox, gathered by the caller. */
data class MessageContext(
    /** The message replies to (References/In-Reply-To) something I sent. */
    val repliesToMe: Boolean = false,
    /** I have written to this sender before — they are a real correspondent, not a service. */
    val knownCorrespondent: Boolean = false,
    /** The message is addressed to me directly (To, not only Cc or a list). */
    val directToMe: Boolean = true,
    /** The sender is one of my own addresses. */
    val fromMe: Boolean = false,
)

data class CategoryVerdict(val category: Category, val confidence: Float, val reasons: List<String>)

/**
 * Explainable, on-device categorisation: weighted evidence per category, the strongest wins, and
 * the reasons that produced the score are kept so the UI can say *why* ("List-Unsubscribe header",
 * "schema.org FlightReservation") and a person can correct it with one tap.
 *
 * Deterministic and instant — it runs inside sync for every new message on any phone, and it is
 * what the on-device and cloud models are measured against (they may only override it when they
 * are confident and it is not).
 */
object Categorizer {

    private class Scores {
        val map = HashMap<Category, Float>()
        val reasons = HashMap<Category, MutableList<String>>()
        fun add(c: Category, w: Float, why: String) {
            map[c] = (map[c] ?: 0f) + w
            reasons.getOrPut(c) { mutableListOf() } += why
        }
    }

    private fun rx(vararg words: String) = Regex("(?i)\\b(" + words.joinToString("|") + ")\\b")

    private val OTP_WORDS = rx("verification code", "one-time (pass)?code", "otp", "security code", "sign[- ]?in code", "login code",
        "passcode", "confirm your email", "verify your (email|account)", "magic link", "sign[- ]?in link", "reset your password", "password reset")
    private val SECURITY_WORDS = rx("new sign[- ]?in", "security alert", "suspicious", "unusual (sign[- ]?in|activity)", "password (was )?changed",
        "new device", "2-step verification", "two-factor authentication (is )?(on|enabled|disabled)", "account (was )?accessed", "recovery email")
    private val TRAVEL_WORDS = rx("itinerary", "boarding pass", "e-?ticket", "flight", "check-?in (now|online|opens)", "booking confirmation",
        "reservation confirmed", "your (trip|stay|booking|reservation)", "pnr", "gate", "departure", "hotel", "check-?out", "car rental", "layover")
    private val TICKET_WORDS = rx("ticket", "case", "request #?\\d+", "support request", "has been (received|updated|resolved)",
        "replied to your (request|ticket)", "help ?desk", "service desk", "issue", "pull request", "assigned to you", "review requested")
    private val RECEIPT_WORDS = rx("receipt", "invoice", "order (confirmation|confirmed|#)", "your order", "payment (received|confirmation)",
        "thanks for (your )?(order|purchase)", "purchase", "subscription (renewed|confirmed)", "billing", "paid")
    private val SHIPPING_WORDS = rx("shipped", "out for delivery", "delivered", "tracking (number|id)", "your (package|parcel|shipment)",
        "on its way", "dispatched", "estimated delivery", "delivery (attempt|update)", "arriving")
    private val EVENT_WORDS = rx("invitation", "invited you", "rsvp", "accepted", "declined", "updated invitation", "event", "webinar",
        "meeting", "calendar", "your tickets", "registration confirmed")
    private val FINANCE_WORDS = rx("statement", "balance", "payment due", "transaction", "deposit", "withdrawal", "credit card", "bank",
        "transfer", "direct debit", "your bill", "tax", "dividend", "portfolio", "account summary", "overdraft", "upi")
    private val PROMO_WORDS = rx("\\d{1,2}% off", "sale", "deal", "limited time", "free shipping", "coupon", "promo code", "exclusive offer",
        "shop now", "black friday", "cyber monday", "save \\$?\\d+", "new arrivals", "last chance", "don't miss", "clearance", "flash sale")
    private val SOCIAL_WORDS = rx("mentioned you", "commented on", "liked your", "new follower", "followed you", "invited you to connect",
        "tagged you", "reacted to", "shared a post", "friend request", "replied to your (post|comment|tweet)", "new connection")
    private val NEWSLETTER_WORDS = rx("newsletter", "digest", "weekly", "issue #?\\d+", "edition", "read in browser", "view (this )?(email|online) in")

    private val TRAVEL_DOMAINS = setOf("airbnb.com", "booking.com", "expedia.com", "united.com", "delta.com", "aa.com", "lufthansa.com",
        "ryanair.com", "easyjet.com", "emirates.com", "marriott.com", "hilton.com", "hyatt.com", "ihg.com", "kayak.com", "tripit.com",
        "hotels.com", "agoda.com", "makemytrip.com", "goindigo.in", "airindia.com", "britishairways.com", "klm.com", "airfrance.fr",
        "qatarairways.com", "singaporeair.com", "southwest.com", "jetblue.com", "alaskaair.com", "trainline.com", "amtrak.com",
        "eurostar.com", "sncf-connect.com", "bahn.de", "flixbus.com", "hertz.com", "avis.com", "sixt.com", "enterprise.com", "vrbo.com",
        "trip.com", "skyscanner.net", "qantas.com", "aircanada.com", "turkishairlines.com", "etihad.com", "irctc.co.in", "cleartrip.com")
    private val SHIPPING_DOMAINS = setOf("ups.com", "fedex.com", "usps.com", "dhl.com", "royalmail.com", "canadapost.ca", "auspost.com.au",
        "dpd.com", "gls-group.eu", "hermesworld.com", "evri.com", "delhivery.com", "bluedart.com", "ontrac.com", "aftership.com",
        "narvar.com", "shipbob.com", "17track.net", "postnl.nl", "laposte.fr", "correos.es", "poste.it")
    private val FINANCE_DOMAINS = setOf("chase.com", "bankofamerica.com", "wellsfargo.com", "citi.com", "americanexpress.com", "capitalone.com",
        "paypal.com", "venmo.com", "wise.com", "revolut.com", "monzo.com", "schwab.com", "fidelity.com", "vanguard.com", "robinhood.com",
        "coinbase.com", "hdfcbank.net", "icicibank.com", "sbi.co.in", "hsbc.com", "barclays.co.uk", "discover.com", "usbank.com",
        "ally.com", "sofi.com", "n26.com", "ing.com", "santander.com", "lloydsbank.co.uk", "natwest.com", "kotak.com", "axisbank.com")
    private val SOCIAL_DOMAINS = setOf("linkedin.com", "facebookmail.com", "facebook.com", "instagram.com", "x.com", "twitter.com", "reddit.com",
        "redditmail.com", "discord.com", "mastodon.social", "tiktok.com", "pinterest.com", "nextdoor.com", "quora.com", "strava.com",
        "meetup.com", "threads.net", "bsky.app", "snapchat.com", "youtube.com")
    private val TICKET_DOMAINS = setOf("zendesk.com", "freshdesk.com", "intercom.io", "intercom-mail.com", "atlassian.net", "atlassian.com",
        "linear.app", "github.com", "gitlab.com", "helpscout.net", "help.com", "hubspot.com", "servicenow.com", "kustomer.com", "gorgias.com", "front.com")
    private val EVENT_DOMAINS = setOf("eventbrite.com", "ticketmaster.com", "livenation.com", "stubhub.com", "meetup.com", "lu.ma", "dice.fm",
        "bookmyshow.com", "calendly.com", "zoom.us", "seetickets.com", "axs.com", "universe.com", "tito.io")
    private val NEWSLETTER_DOMAINS = setOf("substack.com", "beehiiv.com", "buttondown.email", "ghost.io", "convertkit.com", "mailchimpapp.com",
        "medium.com", "morningbrew.com", "revue.email", "every.to", "stratechery.com")

    private val NOREPLY = Regex("(?i)^(no-?reply|do-?not-?reply|notifications?|notify|alerts?|mailer|automated|updates?|news(letter)?|info|hello|team|support|marketing|bounce)")

    fun classify(
        h: MessageHeader,
        text: String?,
        ctx: MessageContext,
        otp: OtpFinding?,
        cards: List<StructuredCard>,
    ): CategoryVerdict {
        val s = Scores()
        val subject = h.subject
        val body = text.orEmpty().take(6000)
        val hay = "$subject\n$body"
        val domain = h.from?.domain.orEmpty()
        val baseDomain = domain.split('.').takeLast(2).joinToString(".")
        val local = h.from?.localPart.orEmpty()
        val automatedSender = NOREPLY.containsMatchIn(local) || h.isAutomated || h.isBulk
        fun domainIn(set: Set<String>) = domain in set || baseDomain in set || set.any { domain.endsWith(".$it") }

        // ---- the decisive signals ----
        cards.forEach { c -> s.add(c.category, 3f, "schema.org ${c.type}") }
        if (otp != null) {
            if (otp.code != null) s.add(Category.OTP, 2.2f + otp.confidence, "one-time code \"${otp.code}\"")
            else s.add(Category.OTP, 1.8f, "sign-in link")
        }
        if (ctx.repliesToMe) s.add(Category.REPLIES, 3.2f, "replies to a message you sent")
        if (h.hasCalendar) s.add(Category.EVENTS, 2.5f, "calendar invitation attached")
        if ("github" in h.serviceHints || "jira" in h.serviceHints || "linear" in h.serviceHints) s.add(Category.TICKETS, 2.2f, "issue tracker")
        if ("zendesk" in h.serviceHints || "freshdesk" in h.serviceHints) s.add(Category.TICKETS, 2.6f, "help desk")

        // ---- sender identity ----
        if (domainIn(TRAVEL_DOMAINS)) s.add(Category.TRAVEL, 1.4f, "travel company")
        if (domainIn(SHIPPING_DOMAINS)) s.add(Category.SHIPPING, 1.6f, "carrier")
        if (domainIn(FINANCE_DOMAINS)) s.add(Category.FINANCE, 1.5f, "bank or payments")
        if (domainIn(SOCIAL_DOMAINS)) s.add(Category.SOCIAL, 1.7f, "social network")
        if (domainIn(TICKET_DOMAINS)) s.add(Category.TICKETS, 1.3f, "support platform")
        if (domainIn(EVENT_DOMAINS)) s.add(Category.EVENTS, 1.4f, "events platform")
        if (domainIn(NEWSLETTER_DOMAINS)) s.add(Category.NEWSLETTERS, 1.6f, "newsletter platform")

        // ---- words ----
        fun words(rx: Regex, c: Category, w: Float, label: String) {
            val inSubject = rx.findAll(subject).count()
            val inBody = rx.findAll(body.take(2500)).count().coerceAtMost(4)
            if (inSubject + inBody > 0) s.add(c, w * (inSubject * 1.5f + inBody * 0.5f).coerceAtMost(3f), label)
        }
        words(OTP_WORDS, Category.OTP, 0.6f, "code wording")
        words(SECURITY_WORDS, Category.SECURITY, 0.9f, "security wording")
        words(TRAVEL_WORDS, Category.TRAVEL, 0.45f, "travel wording")
        words(TICKET_WORDS, Category.TICKETS, 0.4f, "ticket wording")
        words(RECEIPT_WORDS, Category.RECEIPTS, 0.55f, "receipt wording")
        words(SHIPPING_WORDS, Category.SHIPPING, 0.6f, "delivery wording")
        words(EVENT_WORDS, Category.EVENTS, 0.35f, "event wording")
        words(FINANCE_WORDS, Category.FINANCE, 0.35f, "finance wording")
        words(PROMO_WORDS, Category.PROMOTIONS, 0.55f, "promotional wording")
        words(SOCIAL_WORDS, Category.SOCIAL, 0.8f, "social activity")
        words(NEWSLETTER_WORDS, Category.NEWSLETTERS, 0.4f, "newsletter wording")

        if (Regex("(?i)\\[#?\\d{3,}\\]|\\b(ticket|case|request|incident)\\s*#?\\s*\\d{3,}").containsMatchIn(subject)) s.add(Category.TICKETS, 1.6f, "ticket number")
        if (Regex("\\b1Z[0-9A-Z]{16}\\b|\\b\\d{12}\\b|\\b9\\d{15,21}\\b").containsMatchIn(body) && SHIPPING_WORDS.containsMatchIn(hay)) s.add(Category.SHIPPING, 1f, "tracking number")
        if (Regex("[$€£¥₹]\\s?\\d+[.,]\\d{2}|\\d+[.,]\\d{2}\\s?(USD|EUR|GBP|INR|JPY|CAD|AUD)").containsMatchIn(hay)) {
            s.add(Category.RECEIPTS, 0.5f, "amount"); s.add(Category.FINANCE, 0.25f, "amount")
        }
        if (Regex("(?i)\\b([A-Z]{2}|[A-Z]\\d|\\d[A-Z])\\s?\\d{2,4}\\b").containsMatchIn(subject) && Regex("(?i)flight").containsMatchIn(hay)) s.add(Category.TRAVEL, 1f, "flight number")

        // ---- bulk vs person ----
        if (h.listUnsubscribe != null || h.listId != null) {
            val promo = s.map[Category.PROMOTIONS] ?: 0f
            if (promo > 0.8f) s.add(Category.PROMOTIONS, 1.2f, "marketing list") else s.add(Category.NEWSLETTERS, 1.2f, "mailing list")
        }
        if (!automatedSender && h.listId == null && h.listUnsubscribe == null && !ctx.fromMe) {
            var w = 0.9f
            if (ctx.knownCorrespondent) w += 1.4f
            if (ctx.directToMe) w += 0.4f
            if (subject.startsWith("Re:", true) || subject.startsWith("Fwd:", true)) w += 0.3f
            s.add(Category.PEOPLE, w, if (ctx.knownCorrespondent) "someone you write to" else "personal message")
        } else if (automatedSender && s.map.values.maxOrNull().let { it == null || it < 1f }) {
            s.add(Category.UPDATES, 1f, "automated notification")
        }
        // A reply from a support bot or a person in my own conversation is a reply, first.
        if (ctx.repliesToMe) {
            s.map[Category.PEOPLE]?.let { s.map[Category.PEOPLE] = it * 0.5f }
            s.map[Category.TICKETS]?.let { s.map[Category.TICKETS] = it * 0.7f }
        }

        val ranked = s.map.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return CategoryVerdict(Category.OTHER, 0f, emptyList())
        if (top.value < 0.8f) return CategoryVerdict(Category.OTHER, top.value / 2f, s.reasons[top.key].orEmpty())
        val second = ranked.getOrNull(1)?.value ?: 0f
        val confidence = (top.value / (top.value + second + 0.75f)).coerceIn(0.2f, 0.98f)
        return CategoryVerdict(top.key, confidence, s.reasons[top.key].orEmpty().distinct())
    }
}
