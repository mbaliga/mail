import Foundation

public struct ProviderPreset: Equatable, Sendable {
    public let id: String
    public let name: String
    public var domains: Set<String> = []
    /// MX host suffixes that identify a custom domain hosted here (Google Workspace, Microsoft 365...).
    public var mxSuffixes: Set<String> = []
    public var protocolKind: IncomingProtocol = .imap
    public var imap: ServerTemplate?
    public var smtp: ServerTemplate?
    public var jmapSessionUrl: String?
    /// Sign-in methods, best first.
    public var auth: [AuthKind] = [.password]
    public var appPasswordUrl: String?
    /// Shown verbatim by the setup flow, so written for a person, not a log.
    public var setupSteps: [String] = []
    public var localPartUsername = false
    public var supported = true
    public var unsupportedReason: String?

    public func username(for email: String) -> String {
        let e = email.trimmingCharacters(in: .whitespaces)
        guard localPartUsername, let at = e.firstIndex(of: "@") else { return e }
        return String(e[..<at])
    }

    /// Whether this build of the iOS app can sign in to this provider today. OAuth (Gmail/Outlook
    /// sign-in with the provider), JMAP and STARTTLS are not ported yet; app passwords and passwords over implicit TLS are.
    public var signInSupportedOnIOS: Bool {
        supported && protocolKind == .imap && imap?.security == .sslTls
            && (auth.contains(.appPassword) || auth.contains(.password))
    }
}

/// Provider table. A faithful SUBSET of `Providers` in mail-core/discovery/Providers.kt: the account
/// picker's featured shortlist plus the larger regional hosts. The remaining Kotlin entries are a
/// mechanical follow-up (docs/PORTING_PLAN.md); keep ids and hosts identical when adding.
public enum Providers {
    public static let gmail = ProviderPreset(
        id: "gmail", name: "Gmail", domains: ["gmail.com", "googlemail.com"],
        mxSuffixes: ["google.com", "googlemail.com", "smtp.goog"],
        imap: ServerTemplate("imap.gmail.com", 993, .sslTls), smtp: ServerTemplate("smtp.gmail.com", 465, .sslTls),
        auth: [.oauth2, .appPassword], appPasswordUrl: "https://myaccount.google.com/apppasswords",
        setupSteps: [
            "Sign in with Google, or use an app password: turn on 2-Step Verification, then create an app password at myaccount.google.com/apppasswords.",
            "Google Workspace admins may need to allow IMAP for your organisation.",
        ])

    public static let icloud = ProviderPreset(
        id: "icloud", name: "iCloud", domains: ["icloud.com", "me.com", "mac.com"],
        mxSuffixes: ["mail.icloud.com", "icloud.com"],
        imap: ServerTemplate("imap.mail.me.com", 993, .sslTls), smtp: ServerTemplate("smtp.mail.me.com", 587, .startTls),
        auth: [.appPassword], appPasswordUrl: "https://account.apple.com/account/manage",
        setupSteps: [
            "iCloud needs an app-specific password. Two-factor authentication must be on for your Apple Account.",
            "Go to account.apple.com \u{2192} Sign-In and Security \u{2192} App-Specific Passwords, create one named \"You've got Mail\", and paste it here.",
            "Use your full iCloud address as the username. Resetting your Apple Account password revokes app-specific passwords.",
        ])

    public static let outlook = ProviderPreset(
        id: "outlook", name: "Outlook.com",
        domains: ["outlook.com", "hotmail.com", "live.com", "msn.com", "passport.com", "hotmail.co.uk", "hotmail.fr", "hotmail.de",
                  "hotmail.it", "hotmail.es", "live.co.uk", "live.fr", "live.de", "live.nl", "outlook.fr", "outlook.de", "outlook.es",
                  "outlook.it", "outlook.co.uk", "outlook.jp", "outlook.in", "windowslive.com"],
        imap: ServerTemplate("outlook.office365.com", 993, .sslTls), smtp: ServerTemplate("smtp-mail.outlook.com", 587, .startTls),
        auth: [.oauth2],
        setupSteps: ["Microsoft requires signing in with your Microsoft account; passwords are no longer accepted for mail apps."])

    public static let microsoft365 = ProviderPreset(
        id: "m365", name: "Microsoft 365", mxSuffixes: ["mail.protection.outlook.com", "outlook.com"],
        imap: ServerTemplate("outlook.office365.com", 993, .sslTls), smtp: ServerTemplate("smtp.office365.com", 587, .startTls),
        auth: [.oauth2],
        setupSteps: [
            "Sign in with your work or school account.",
            "Your administrator must allow IMAP and SMTP AUTH for your mailbox; some organisations disable them.",
        ])

    public static let yahoo = ProviderPreset(
        id: "yahoo", name: "Yahoo Mail",
        domains: ["yahoo.com", "ymail.com", "rocketmail.com", "yahoo.co.uk", "yahoo.fr", "yahoo.de", "yahoo.it", "yahoo.es",
                  "yahoo.ca", "yahoo.com.au", "yahoo.co.in", "yahoo.com.br", "yahoo.com.mx", "yahoo.co.nz", "yahoo.com.sg"],
        mxSuffixes: ["yahoodns.net"],
        imap: ServerTemplate("imap.mail.yahoo.com", 993, .sslTls), smtp: ServerTemplate("smtp.mail.yahoo.com", 465, .sslTls),
        auth: [.appPassword], appPasswordUrl: "https://login.yahoo.com/account/security",
        setupSteps: ["Create an app password at login.yahoo.com \u{2192} Account Security \u{2192} Generate app password."])

    public static let aol = ProviderPreset(
        id: "aol", name: "AOL Mail", domains: ["aol.com", "aim.com", "aol.co.uk", "aol.de", "aol.fr", "verizon.net"],
        imap: ServerTemplate("imap.aol.com", 993, .sslTls), smtp: ServerTemplate("smtp.aol.com", 465, .sslTls),
        auth: [.appPassword], appPasswordUrl: "https://login.aol.com/account/security",
        setupSteps: ["Create an app password at login.aol.com \u{2192} Account Security \u{2192} Generate app password."])

    public static let fastmail = ProviderPreset(
        id: "fastmail", name: "Fastmail",
        domains: ["fastmail.com", "fastmail.fm", "fastmail.cn", "fastmail.de", "fastmail.es", "fastmail.in", "fastmail.jp",
                  "fastmail.net", "fastmail.org", "fastmail.to", "fastmail.uk", "messagingengine.com", "sent.com"],
        mxSuffixes: ["messagingengine.com"], protocolKind: .jmap,
        imap: ServerTemplate("imap.fastmail.com", 993, .sslTls), smtp: ServerTemplate("smtp.fastmail.com", 465, .sslTls),
        jmapSessionUrl: "https://api.fastmail.com/jmap/session",
        auth: [.apiToken, .appPassword], appPasswordUrl: "https://app.fastmail.com/settings/security/integrations",
        setupSteps: [
            "Fastmail speaks JMAP, the modern mail protocol: create an API token with Mail and Submission access in Settings \u{2192} Privacy & Security \u{2192} Integrations.",
            "Or use an app password for IMAP and SMTP.",
        ])

    public static let proton = ProviderPreset(
        id: "proton", name: "Proton Mail", domains: ["proton.me", "protonmail.com", "protonmail.ch", "pm.me"],
        mxSuffixes: ["protonmail.ch"],
        imap: ServerTemplate("127.0.0.1", 1143, .startTls), smtp: ServerTemplate("127.0.0.1", 1025, .startTls),
        auth: [.password],
        setupSteps: [
            "Proton encrypts end to end and has no IMAP of its own. It works only through Proton Mail Bridge, which runs on a computer.",
            "If Bridge runs on a computer on your network, enter that computer's address and the Bridge password manually.",
        ])

    public static let gmx = ProviderPreset(
        id: "gmx", name: "GMX", domains: ["gmx.com", "gmx.net", "gmx.de", "gmx.at", "gmx.ch", "gmx.co.uk", "gmx.fr", "gmx.es"],
        imap: ServerTemplate("imap.gmx.net", 993, .sslTls), smtp: ServerTemplate("mail.gmx.net", 587, .startTls),
        setupSteps: ["Turn on \"POP3/IMAP access\" in GMX settings \u{2192} POP3 & IMAP before signing in."])

    public static let posteo = ProviderPreset(
        id: "posteo", name: "Posteo", domains: ["posteo.de", "posteo.net", "posteo.at", "posteo.ch", "posteo.eu", "posteo.org", "posteo.uk", "posteo.us"],
        imap: ServerTemplate("posteo.de", 993, .sslTls), smtp: ServerTemplate("posteo.de", 587, .startTls))

    public static let hey = ProviderPreset(
        id: "hey", name: "HEY", domains: ["hey.com"], supported: false,
        unsupportedReason: "HEY offers no IMAP or JMAP access, so no other mail app can read it.")

    public static let all: [ProviderPreset] = [gmail, icloud, outlook, microsoft365, yahoo, aol, fastmail, proton, gmx, posteo, hey]

    /// The account picker's shortlist, in the order people look for them.
    public static let featured: [ProviderPreset] = [gmail, icloud, outlook, microsoft365, yahoo, fastmail, aol, proton]

    public static func byId(_ id: String) -> ProviderPreset? { all.first { $0.id == id } }

    public static func forDomain(_ domain: String) -> ProviderPreset? {
        let d = domain.trimmingCharacters(in: .whitespaces).lowercased()
        return all.first { $0.domains.contains(d) }
    }

    public static func forEmail(_ email: String) -> ProviderPreset? {
        guard let at = email.lastIndex(of: "@") else { return nil }
        return forDomain(String(email[email.index(after: at)...]))
    }

    public static func forMx(_ mxHost: String) -> ProviderPreset? {
        var h = mxHost.trimmingCharacters(in: .whitespaces).lowercased()
        while h.hasSuffix(".") { h.removeLast() }
        // Most specific suffix wins (mail.protection.outlook.com before outlook.com).
        return all.flatMap { p in p.mxSuffixes.map { ($0, p) } }
            .sorted { $0.0.count > $1.0.count }
            .first { h == $0.0 || h.hasSuffix("." + $0.0) }?.1
    }
}
