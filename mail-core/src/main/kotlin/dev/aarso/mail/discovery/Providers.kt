package dev.aarso.mail.discovery

import dev.aarso.mail.model.AuthKind
import dev.aarso.mail.model.IncomingProtocol
import dev.aarso.mail.model.Security

data class ServerTemplate(val host: String, val port: Int, val security: Security)

/** How the username is derived from the address. Most hosts want the whole address. */
enum class UsernameStyle { EMAIL, LOCAL_PART }

/**
 * Everything known about a mail host before talking to it: servers, how sign-in works, and what the
 * person has to do on the provider's side first (enable IMAP, mint an app password). The setup flow
 * shows [setupSteps] verbatim, so they are written for a person, not a log.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val domains: Set<String> = emptySet(),
    /** MX host suffixes that identify a custom domain hosted here (Google Workspace, Microsoft 365…). */
    val mxSuffixes: Set<String> = emptySet(),
    val protocol: IncomingProtocol = IncomingProtocol.IMAP,
    val imap: ServerTemplate? = null,
    val smtp: ServerTemplate? = null,
    val pop3: ServerTemplate? = null,
    val jmapSessionUrl: String? = null,
    /** Sign-in methods, best first. */
    val auth: List<AuthKind> = listOf(AuthKind.PASSWORD),
    val oauthProvider: String? = null,
    val appPasswordUrl: String? = null,
    val setupSteps: List<String> = emptyList(),
    val usernameStyle: UsernameStyle = UsernameStyle.EMAIL,
    /** False for hosts that offer no standard access at all; the setup flow explains why. */
    val supported: Boolean = true,
    val unsupportedReason: String? = null,
) {
    fun username(email: String): String = when (usernameStyle) {
        UsernameStyle.EMAIL -> email.trim()
        UsernameStyle.LOCAL_PART -> email.trim().substringBefore('@')
    }
}

object Providers {

    private fun imap(host: String, port: Int = 993, security: Security = Security.SSL_TLS) = ServerTemplate(host, port, security)
    private fun smtp(host: String, port: Int = 465, security: Security = Security.SSL_TLS) = ServerTemplate(host, port, security)
    private fun smtpStartTls(host: String) = ServerTemplate(host, 587, Security.STARTTLS)

    val GMAIL = ProviderPreset(
        id = "gmail", name = "Gmail",
        domains = setOf("gmail.com", "googlemail.com"),
        mxSuffixes = setOf("google.com", "googlemail.com", "smtp.goog"),
        imap = imap("imap.gmail.com"), smtp = smtp("smtp.gmail.com"),
        auth = listOf(AuthKind.OAUTH2, AuthKind.APP_PASSWORD), oauthProvider = "google",
        appPasswordUrl = "https://myaccount.google.com/apppasswords",
        setupSteps = listOf(
            "Sign in with Google, or use an app password: turn on 2-Step Verification, then create an app password at myaccount.google.com/apppasswords.",
            "Google Workspace admins may need to allow IMAP for your organisation.",
        ),
    )

    val ICLOUD = ProviderPreset(
        id = "icloud", name = "iCloud",
        domains = setOf("icloud.com", "me.com", "mac.com"),
        mxSuffixes = setOf("mail.icloud.com", "icloud.com"),
        imap = imap("imap.mail.me.com"), smtp = smtpStartTls("smtp.mail.me.com"),
        auth = listOf(AuthKind.APP_PASSWORD),
        appPasswordUrl = "https://account.apple.com/account/manage",
        setupSteps = listOf(
            "iCloud needs an app-specific password. Two-factor authentication must be on for your Apple Account.",
            "Go to account.apple.com → Sign-In and Security → App-Specific Passwords, create one named \"You've got Mail\", and paste it here.",
            "Use your full iCloud address as the username. Resetting your Apple Account password revokes app-specific passwords.",
        ),
    )

    val OUTLOOK = ProviderPreset(
        id = "outlook", name = "Outlook.com",
        domains = setOf(
            "outlook.com", "hotmail.com", "live.com", "msn.com", "passport.com", "hotmail.co.uk", "hotmail.fr", "hotmail.de",
            "hotmail.it", "hotmail.es", "live.co.uk", "live.fr", "live.de", "live.nl", "outlook.fr", "outlook.de", "outlook.es",
            "outlook.it", "outlook.co.uk", "outlook.jp", "outlook.in", "windowslive.com",
        ),
        imap = imap("outlook.office365.com"), smtp = smtpStartTls("smtp-mail.outlook.com"),
        auth = listOf(AuthKind.OAUTH2), oauthProvider = "microsoft",
        setupSteps = listOf("Microsoft requires signing in with your Microsoft account; passwords are no longer accepted for mail apps."),
    )

    val MICROSOFT_365 = ProviderPreset(
        id = "m365", name = "Microsoft 365",
        mxSuffixes = setOf("mail.protection.outlook.com", "outlook.com"),
        imap = imap("outlook.office365.com"), smtp = smtpStartTls("smtp.office365.com"),
        auth = listOf(AuthKind.OAUTH2), oauthProvider = "microsoft",
        setupSteps = listOf(
            "Sign in with your work or school account.",
            "Your administrator must allow IMAP and SMTP AUTH for your mailbox; some organisations disable them.",
        ),
    )

    val YAHOO = ProviderPreset(
        id = "yahoo", name = "Yahoo Mail",
        domains = setOf("yahoo.com", "ymail.com", "rocketmail.com", "yahoo.co.uk", "yahoo.fr", "yahoo.de", "yahoo.it", "yahoo.es",
            "yahoo.ca", "yahoo.com.au", "yahoo.co.in", "yahoo.com.br", "yahoo.com.mx", "yahoo.co.nz", "yahoo.com.sg"),
        mxSuffixes = setOf("yahoodns.net"),
        imap = imap("imap.mail.yahoo.com"), smtp = smtp("smtp.mail.yahoo.com"),
        auth = listOf(AuthKind.APP_PASSWORD), appPasswordUrl = "https://login.yahoo.com/account/security",
        setupSteps = listOf("Create an app password at login.yahoo.com → Account Security → Generate app password."),
    )

    val AOL = ProviderPreset(
        id = "aol", name = "AOL Mail",
        domains = setOf("aol.com", "aim.com", "aol.co.uk", "aol.de", "aol.fr", "verizon.net"),
        imap = imap("imap.aol.com"), smtp = smtp("smtp.aol.com"),
        auth = listOf(AuthKind.APP_PASSWORD), appPasswordUrl = "https://login.aol.com/account/security",
        setupSteps = listOf("Create an app password at login.aol.com → Account Security → Generate app password."),
    )

    val FASTMAIL = ProviderPreset(
        id = "fastmail", name = "Fastmail",
        domains = setOf("fastmail.com", "fastmail.fm", "fastmail.cn", "fastmail.de", "fastmail.es", "fastmail.in", "fastmail.jp",
            "fastmail.net", "fastmail.org", "fastmail.to", "fastmail.uk", "messagingengine.com", "sent.com"),
        mxSuffixes = setOf("messagingengine.com"),
        protocol = IncomingProtocol.JMAP,
        jmapSessionUrl = "https://api.fastmail.com/jmap/session",
        imap = imap("imap.fastmail.com"), smtp = smtp("smtp.fastmail.com"),
        auth = listOf(AuthKind.API_TOKEN, AuthKind.APP_PASSWORD),
        appPasswordUrl = "https://app.fastmail.com/settings/security/integrations",
        setupSteps = listOf(
            "Fastmail speaks JMAP, the modern mail protocol: create an API token with Mail and Submission access in Settings → Privacy & Security → Integrations.",
            "Or use an app password for IMAP and SMTP.",
        ),
    )

    val PROTON = ProviderPreset(
        id = "proton", name = "Proton Mail",
        domains = setOf("proton.me", "protonmail.com", "protonmail.ch", "pm.me"),
        mxSuffixes = setOf("protonmail.ch"),
        imap = ServerTemplate("127.0.0.1", 1143, Security.STARTTLS), smtp = ServerTemplate("127.0.0.1", 1025, Security.STARTTLS),
        auth = listOf(AuthKind.PASSWORD),
        setupSteps = listOf(
            "Proton encrypts end to end and has no IMAP of its own. It works only through Proton Mail Bridge, which runs on a computer.",
            "If Bridge runs on a computer on your network, enter that computer's address and the Bridge password manually.",
        ),
    )

    val ZOHO = ProviderPreset(
        id = "zoho", name = "Zoho Mail",
        domains = setOf("zoho.com", "zohomail.com", "zoho.eu", "zohomail.eu", "zoho.in", "zohomail.in"),
        mxSuffixes = setOf("zoho.com", "zoho.eu", "zoho.in"),
        imap = imap("imap.zoho.com"), smtp = smtp("smtp.zoho.com"),
        auth = listOf(AuthKind.APP_PASSWORD, AuthKind.PASSWORD),
        appPasswordUrl = "https://accounts.zoho.com/home#security/app_password",
        setupSteps = listOf("Enable IMAP access in Zoho Mail settings. With two-factor sign-in, create an application-specific password."),
    )

    val GMX = ProviderPreset(
        id = "gmx", name = "GMX",
        domains = setOf("gmx.com", "gmx.net", "gmx.de", "gmx.at", "gmx.ch", "gmx.co.uk", "gmx.fr", "gmx.es"),
        imap = imap("imap.gmx.net"), smtp = smtpStartTls("mail.gmx.net"),
        setupSteps = listOf("Turn on \"POP3/IMAP access\" in GMX settings → POP3 & IMAP before signing in."),
    )

    val WEBDE = ProviderPreset(
        id = "webde", name = "WEB.DE",
        domains = setOf("web.de"),
        imap = imap("imap.web.de"), smtp = smtpStartTls("smtp.web.de"),
        setupSteps = listOf("Turn on \"POP3/IMAP Abruf\" in WEB.DE settings before signing in."),
    )

    val MAILCOM = ProviderPreset(
        id = "mailcom", name = "mail.com",
        domains = setOf("mail.com", "email.com", "usa.com", "post.com", "consultant.com", "myself.com", "europe.com"),
        imap = imap("imap.mail.com"), smtp = smtpStartTls("smtp.mail.com"),
    )

    val YANDEX = ProviderPreset(
        id = "yandex", name = "Yandex Mail",
        domains = setOf("yandex.com", "yandex.ru", "ya.ru", "yandex.ua", "yandex.by", "yandex.kz"),
        mxSuffixes = setOf("yandex.net", "yandex.ru"),
        imap = imap("imap.yandex.com"), smtp = smtp("smtp.yandex.com"),
        auth = listOf(AuthKind.APP_PASSWORD), appPasswordUrl = "https://id.yandex.com/security/app-passwords",
    )

    val MAILRU = ProviderPreset(
        id = "mailru", name = "Mail.ru",
        domains = setOf("mail.ru", "inbox.ru", "list.ru", "bk.ru"),
        imap = imap("imap.mail.ru"), smtp = smtp("smtp.mail.ru"),
        auth = listOf(AuthKind.APP_PASSWORD),
    )

    val QQ = ProviderPreset(
        id = "qq", name = "QQ Mail",
        domains = setOf("qq.com", "foxmail.com"),
        imap = imap("imap.qq.com"), smtp = smtp("smtp.qq.com"),
        auth = listOf(AuthKind.APP_PASSWORD),
        setupSteps = listOf("Enable IMAP/SMTP in QQ Mail settings and use the authorization code it gives you as the password."),
    )

    val NETEASE = ProviderPreset(
        id = "netease", name = "NetEase Mail",
        domains = setOf("163.com", "126.com", "yeah.net"),
        imap = imap("imap.163.com"), smtp = smtp("smtp.163.com"),
        auth = listOf(AuthKind.APP_PASSWORD),
        setupSteps = listOf("Enable IMAP/SMTP in NetEase settings and use the client authorization code as the password."),
    )

    val POSTEO = ProviderPreset(
        id = "posteo", name = "Posteo",
        domains = setOf("posteo.de", "posteo.net", "posteo.at", "posteo.ch", "posteo.eu", "posteo.org", "posteo.uk", "posteo.us"),
        imap = imap("posteo.de"), smtp = smtpStartTls("posteo.de"),
    )

    val MAILBOX_ORG = ProviderPreset(
        id = "mailboxorg", name = "mailbox.org",
        domains = setOf("mailbox.org"),
        mxSuffixes = setOf("mailbox.org"),
        imap = imap("imap.mailbox.org"), smtp = smtp("smtp.mailbox.org"),
    )

    val COMCAST = ProviderPreset(
        id = "comcast", name = "Xfinity Connect",
        domains = setOf("comcast.net"),
        imap = imap("imap.comcast.net"), smtp = smtpStartTls("smtp.comcast.net"),
        setupSteps = listOf("Turn on \"Third Party Access Security\" in Xfinity Connect settings."),
    )

    val ATT = ProviderPreset(
        id = "att", name = "AT&T Mail",
        domains = setOf("att.net", "sbcglobal.net", "bellsouth.net", "pacbell.net", "flash.net", "swbell.net", "ameritech.net"),
        imap = imap("imap.mail.att.net"), smtp = smtp("smtp.mail.att.net"),
        auth = listOf(AuthKind.APP_PASSWORD),
        setupSteps = listOf("Create a secure mail key in your AT&T profile and use it as the password."),
    )

    val TONLINE = ProviderPreset(
        id = "tonline", name = "T-Online",
        domains = setOf("t-online.de", "magenta.de"),
        imap = imap("secureimap.t-online.de"), smtp = smtp("securesmtp.t-online.de"),
        setupSteps = listOf("Set an email password (\"E-Mail-Passwort\") in the T-Online customer centre first."),
    )

    val ORANGE = ProviderPreset(id = "orange", name = "Orange", domains = setOf("orange.fr", "wanadoo.fr"),
        imap = imap("imap.orange.fr"), smtp = smtp("smtp.orange.fr"))
    val FREE = ProviderPreset(id = "free", name = "Free", domains = setOf("free.fr"),
        imap = imap("imap.free.fr"), smtp = smtp("smtp.free.fr"))
    val LIBERO = ProviderPreset(id = "libero", name = "Libero", domains = setOf("libero.it", "inwind.it", "blu.it", "iol.it"),
        imap = imap("imapmail.libero.it"), smtp = smtp("smtp.libero.it"))
    val SEZNAM = ProviderPreset(id = "seznam", name = "Seznam", domains = setOf("seznam.cz", "email.cz", "post.cz"),
        imap = imap("imap.seznam.cz"), smtp = smtp("smtp.seznam.cz"))
    val NAVER = ProviderPreset(id = "naver", name = "Naver", domains = setOf("naver.com"),
        imap = imap("imap.naver.com"), smtp = smtpStartTls("smtp.naver.com"), usernameStyle = UsernameStyle.LOCAL_PART,
        setupSteps = listOf("Enable IMAP/SMTP in Naver Mail settings."))

    val TUTA = ProviderPreset(
        id = "tuta", name = "Tuta", domains = setOf("tuta.com", "tutanota.com", "tutanota.de", "tutamail.com", "tuta.io", "keemail.me"),
        supported = false, unsupportedReason = "Tuta offers no IMAP or JMAP access, so no other mail app can read it.",
    )

    val HEY = ProviderPreset(
        id = "hey", name = "HEY", domains = setOf("hey.com"),
        supported = false, unsupportedReason = "HEY offers no IMAP or JMAP access, so no other mail app can read it.",
    )

    val all: List<ProviderPreset> = listOf(
        GMAIL, ICLOUD, OUTLOOK, MICROSOFT_365, YAHOO, AOL, FASTMAIL, PROTON, ZOHO, GMX, WEBDE, MAILCOM, YANDEX, MAILRU, QQ, NETEASE,
        POSTEO, MAILBOX_ORG, COMCAST, ATT, TONLINE, ORANGE, FREE, LIBERO, SEZNAM, NAVER, TUTA, HEY,
    )

    /** The account picker's shortlist, in the order people look for them. */
    val featured: List<ProviderPreset> = listOf(GMAIL, ICLOUD, OUTLOOK, MICROSOFT_365, YAHOO, FASTMAIL, AOL, PROTON)

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }

    fun forDomain(domain: String): ProviderPreset? {
        val d = domain.trim().lowercase()
        return all.firstOrNull { d in it.domains }
    }

    fun forMx(mxHost: String): ProviderPreset? {
        val h = mxHost.trim().trimEnd('.').lowercase()
        // Most specific suffix wins (mail.protection.outlook.com before outlook.com).
        return all.flatMap { p -> p.mxSuffixes.map { it to p } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (suffix, _) -> h == suffix || h.endsWith(".$suffix") }?.second
    }
}
