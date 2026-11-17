//! Provider table. A faithful SUBSET of `Providers` in mail-core/discovery/Providers.kt (same as the
//! Swift port): ids and hosts are kept identical; the remaining Kotlin entries are a mechanical follow-up.

use serde_json::{json, Value};

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Security {
    SslTls,
    StartTls,
}
impl Security {
    fn as_str(self) -> &'static str {
        match self {
            Security::SslTls => "sslTls",
            Security::StartTls => "startTls",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ServerTemplate {
    pub host: &'static str,
    pub port: u16,
    pub security: Security,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Proto {
    Imap,
    Jmap,
}

pub struct ProviderPreset {
    pub id: &'static str,
    pub name: &'static str,
    pub domains: &'static [&'static str],
    pub mx_suffixes: &'static [&'static str],
    pub proto: Proto,
    pub imap: Option<ServerTemplate>,
    pub smtp: Option<ServerTemplate>,
    /// "oauth2", "appPassword", "password", "apiToken"; best first.
    pub auth: &'static [&'static str],
    pub app_password_url: Option<&'static str>,
    pub setup_steps: &'static [&'static str],
    pub supported: bool,
    pub unsupported_reason: Option<&'static str>,
}

const fn t(host: &'static str, port: u16, security: Security) -> Option<ServerTemplate> {
    Some(ServerTemplate { host, port, security })
}
use Security::{SslTls, StartTls};

impl ProviderPreset {
    /// Whether THIS port can sign in today: IMAP with an app password / password. OAuth and JMAP
    /// are not ported (see docs/PORTING_PLAN_UT.md), so those providers are shown but not offered.
    pub fn sign_in_supported(&self) -> bool {
        self.supported && self.proto == Proto::Imap && self.imap.is_some() && (self.auth.contains(&"appPassword") || self.auth.contains(&"password"))
    }
    pub fn to_json(&self) -> Value {
        let tmpl = |s: &Option<ServerTemplate>| s.map(|s| json!({"host": s.host, "port": s.port, "security": s.security.as_str()}));
        json!({
            "id": self.id, "name": self.name, "imap": tmpl(&self.imap), "smtp": tmpl(&self.smtp),
            "auth": self.auth, "appPasswordUrl": self.app_password_url, "setupSteps": self.setup_steps,
            "supported": self.supported, "unsupportedReason": self.unsupported_reason,
            "signInSupported": self.sign_in_supported(),
        })
    }
}

macro_rules! p { ($($k:ident : $v:expr),* $(,)?) => { ProviderPreset { $($k: $v),* } }; }

pub static ALL: &[ProviderPreset] = &[
    p! { id: "gmail", name: "Gmail", domains: &["gmail.com", "googlemail.com"], mx_suffixes: &["google.com", "googlemail.com", "smtp.goog"],
        proto: Proto::Imap, imap: t("imap.gmail.com", 993, SslTls), smtp: t("smtp.gmail.com", 465, SslTls), auth: &["oauth2", "appPassword"],
        app_password_url: Some("https://myaccount.google.com/apppasswords"),
        setup_steps: &["Sign in with Google, or use an app password: turn on 2-Step Verification, then create an app password at myaccount.google.com/apppasswords.",
                       "Google Workspace admins may need to allow IMAP for your organisation."],
        supported: true, unsupported_reason: None },
    p! { id: "icloud", name: "iCloud", domains: &["icloud.com", "me.com", "mac.com"], mx_suffixes: &["mail.icloud.com", "icloud.com"],
        proto: Proto::Imap, imap: t("imap.mail.me.com", 993, SslTls), smtp: t("smtp.mail.me.com", 587, StartTls), auth: &["appPassword"],
        app_password_url: Some("https://account.apple.com/account/manage"),
        setup_steps: &["iCloud needs an app-specific password. Two-factor authentication must be on for your Apple Account.",
                       "Go to account.apple.com \u{2192} Sign-In and Security \u{2192} App-Specific Passwords, create one named \"You've got Mail\", and paste it here.",
                       "Use your full iCloud address as the username. Resetting your Apple Account password revokes app-specific passwords."],
        supported: true, unsupported_reason: None },
    p! { id: "outlook", name: "Outlook.com",
        domains: &["outlook.com", "hotmail.com", "live.com", "msn.com", "passport.com", "hotmail.co.uk", "hotmail.fr", "hotmail.de", "hotmail.it", "hotmail.es",
                   "live.co.uk", "live.fr", "live.de", "live.nl", "outlook.fr", "outlook.de", "outlook.es", "outlook.it", "outlook.co.uk", "outlook.jp", "outlook.in", "windowslive.com"],
        mx_suffixes: &[], proto: Proto::Imap, imap: t("outlook.office365.com", 993, SslTls), smtp: t("smtp-mail.outlook.com", 587, StartTls), auth: &["oauth2"],
        app_password_url: None, setup_steps: &["Microsoft requires signing in with your Microsoft account; passwords are no longer accepted for mail apps."],
        supported: true, unsupported_reason: None },
    p! { id: "m365", name: "Microsoft 365", domains: &[], mx_suffixes: &["mail.protection.outlook.com", "outlook.com"],
        proto: Proto::Imap, imap: t("outlook.office365.com", 993, SslTls), smtp: t("smtp.office365.com", 587, StartTls), auth: &["oauth2"], app_password_url: None,
        setup_steps: &["Sign in with your work or school account.",
                       "Your administrator must allow IMAP and SMTP AUTH for your mailbox; some organisations disable them."],
        supported: true, unsupported_reason: None },
    p! { id: "yahoo", name: "Yahoo Mail",
        domains: &["yahoo.com", "ymail.com", "rocketmail.com", "yahoo.co.uk", "yahoo.fr", "yahoo.de", "yahoo.it", "yahoo.es", "yahoo.ca", "yahoo.com.au",
                   "yahoo.co.in", "yahoo.com.br", "yahoo.com.mx", "yahoo.co.nz", "yahoo.com.sg"],
        mx_suffixes: &["yahoodns.net"], proto: Proto::Imap, imap: t("imap.mail.yahoo.com", 993, SslTls), smtp: t("smtp.mail.yahoo.com", 465, SslTls), auth: &["appPassword"],
        app_password_url: Some("https://login.yahoo.com/account/security"),
        setup_steps: &["Create an app password at login.yahoo.com \u{2192} Account Security \u{2192} Generate app password."], supported: true, unsupported_reason: None },
    p! { id: "aol", name: "AOL Mail", domains: &["aol.com", "aim.com", "aol.co.uk", "aol.de", "aol.fr", "verizon.net"], mx_suffixes: &[],
        proto: Proto::Imap, imap: t("imap.aol.com", 993, SslTls), smtp: t("smtp.aol.com", 465, SslTls), auth: &["appPassword"],
        app_password_url: Some("https://login.aol.com/account/security"),
        setup_steps: &["Create an app password at login.aol.com \u{2192} Account Security \u{2192} Generate app password."], supported: true, unsupported_reason: None },
    p! { id: "fastmail", name: "Fastmail",
        domains: &["fastmail.com", "fastmail.fm", "fastmail.cn", "fastmail.de", "fastmail.es", "fastmail.in", "fastmail.jp", "fastmail.net", "fastmail.org",
                   "fastmail.to", "fastmail.uk", "messagingengine.com", "sent.com"],
        mx_suffixes: &["messagingengine.com"], proto: Proto::Jmap, imap: t("imap.fastmail.com", 993, SslTls), smtp: t("smtp.fastmail.com", 465, SslTls),
        auth: &["apiToken", "appPassword"], app_password_url: Some("https://app.fastmail.com/settings/security/integrations"),
        setup_steps: &["Fastmail speaks JMAP, the modern mail protocol: create an API token with Mail and Submission access in Settings \u{2192} Privacy & Security \u{2192} Integrations.",
                       "Or use an app password for IMAP and SMTP."],
        supported: true, unsupported_reason: None },
    p! { id: "proton", name: "Proton Mail", domains: &["proton.me", "protonmail.com", "protonmail.ch", "pm.me"], mx_suffixes: &["protonmail.ch"],
        proto: Proto::Imap, imap: t("127.0.0.1", 1143, StartTls), smtp: t("127.0.0.1", 1025, StartTls), auth: &["password"], app_password_url: None,
        setup_steps: &["Proton encrypts end to end and has no IMAP of its own. It works only through Proton Mail Bridge, which runs on a computer.",
                       "If Bridge runs on a computer on your network, enter that computer's address and the Bridge password manually."],
        supported: true, unsupported_reason: None },
    p! { id: "gmx", name: "GMX", domains: &["gmx.com", "gmx.net", "gmx.de", "gmx.at", "gmx.ch", "gmx.co.uk", "gmx.fr", "gmx.es"], mx_suffixes: &[],
        proto: Proto::Imap, imap: t("imap.gmx.net", 993, SslTls), smtp: t("mail.gmx.net", 587, StartTls), auth: &["password"], app_password_url: None,
        setup_steps: &["Turn on \"POP3/IMAP access\" in GMX settings \u{2192} POP3 & IMAP before signing in."], supported: true, unsupported_reason: None },
    p! { id: "posteo", name: "Posteo", domains: &["posteo.de", "posteo.net", "posteo.at", "posteo.ch", "posteo.eu", "posteo.org", "posteo.uk", "posteo.us"], mx_suffixes: &[],
        proto: Proto::Imap, imap: t("posteo.de", 993, SslTls), smtp: t("posteo.de", 587, StartTls), auth: &["password"], app_password_url: None,
        setup_steps: &[], supported: true, unsupported_reason: None },
    p! { id: "hey", name: "HEY", domains: &["hey.com"], mx_suffixes: &[], proto: Proto::Imap, imap: None, smtp: None, auth: &["password"], app_password_url: None,
        setup_steps: &[], supported: false, unsupported_reason: Some("HEY offers no IMAP or JMAP access, so no other mail app can read it.") },
];

pub fn by_id(id: &str) -> Option<&'static ProviderPreset> {
    ALL.iter().find(|p| p.id == id)
}
pub fn for_domain(domain: &str) -> Option<&'static ProviderPreset> {
    let d = domain.trim().to_lowercase();
    ALL.iter().find(|p| p.domains.contains(&d.as_str()))
}
pub fn for_email(email: &str) -> Option<&'static ProviderPreset> {
    let at = email.rfind('@')?;
    for_domain(&email[at + 1..])
}
/// Most specific MX suffix wins (mail.protection.outlook.com before outlook.com).
pub fn for_mx(mx_host: &str) -> Option<&'static ProviderPreset> {
    let h = mx_host.trim().to_lowercase();
    let h = h.trim_end_matches('.');
    let mut cands: Vec<(&str, &ProviderPreset)> = ALL.iter().flat_map(|p| p.mx_suffixes.iter().map(move |s| (*s, p))).collect();
    cands.sort_by(|a, b| b.0.len().cmp(&a.0.len()));
    cands.into_iter().find(|(s, _)| h == *s || h.ends_with(&format!(".{s}"))).map(|(_, p)| p)
}
/// Username to log in with: the full address, as every shipped preset expects.
pub fn username_for(email: &str) -> String {
    email.trim().to_string()
}
