use mailcore::envelope::{parse_date, parse_message_ids};
use mailcore::otp::find_otp;
use mailcore::providers::*;

#[test]
fn otp_found_near_keyword_with_validity() {
    let f = find_otp("Your verification code", "Your verification code is 482913. It expires in 10 minutes.", Some("Acme <no-reply@acme.com>")).unwrap();
    assert_eq!(f.code.as_deref(), Some("482913"));
    assert!(!f.is_magic_link);
    assert_eq!(f.service.as_deref(), Some("Acme"));
    assert_eq!(f.valid_minutes, Some(10));
    assert!(f.confidence > 0.5);
}

#[test]
fn otp_ignores_years_order_numbers_and_mail_with_no_keyword() {
    assert!(find_otp("Hello", "Copyright 2024 verify", None).is_none());
    assert!(find_otp("Hello", "Your order 4829173 verify", None).is_none());
    assert!(find_otp("Meeting", "Room 123456 at noon", None).is_none());
}

#[test]
fn magic_link_without_code() {
    let f = find_otp("Welcome back", "Click the button below to sign in.", None).unwrap();
    assert!(f.code.is_none());
    assert!(f.is_magic_link);
}

#[test]
fn otp_hours_and_grouped_codes() {
    let f = find_otp("Security code", "Your security code: 123 456\nvalid for 2 hours", None).unwrap();
    assert_eq!(f.code.as_deref(), Some("123 456"));
    assert_eq!(f.valid_minutes, Some(120));
}

#[test]
fn provider_lookup_by_domain_email_and_mx() {
    assert_eq!(for_email("ada@icloud.com").unwrap().id, "icloud");
    assert_eq!(for_email("ada@GMAIL.com ").unwrap().id, "gmail");
    assert!(for_email("ada@example.org").is_none());
    assert!(for_email("not-an-address").is_none());
    assert_eq!(for_mx("mail.protection.outlook.com.").unwrap().id, "m365");
    assert_eq!(for_mx("aspmx.l.google.com").unwrap().id, "gmail");
}

#[test]
fn provider_settings_match_the_android_table() {
    let icloud = by_id("icloud").unwrap();
    assert_eq!(icloud.imap, Some(ServerTemplate { host: "imap.mail.me.com", port: 993, security: Security::SslTls }));
    assert_eq!(icloud.smtp, Some(ServerTemplate { host: "smtp.mail.me.com", port: 587, security: Security::StartTls }));
    assert_eq!(username_for(" ada@icloud.com "), "ada@icloud.com");
    // TLS is never downgraded: every shipped IMAP template except the local Proton Bridge is implicit TLS.
    for p in ALL.iter().filter(|p| p.id != "proton") {
        if let Some(i) = p.imap {
            assert_eq!(i.security, Security::SslTls, "{}", p.id);
        }
    }
}

#[test]
fn sign_in_gating_is_honest() {
    assert!(by_id("icloud").unwrap().sign_in_supported()); // app password over IMAP
    assert!(by_id("yahoo").unwrap().sign_in_supported());
    assert!(!by_id("outlook").unwrap().sign_in_supported()); // OAuth-only: not ported
    assert!(!by_id("hey").unwrap().sign_in_supported()); // no IMAP at all
    assert!(!by_id("fastmail").unwrap().sign_in_supported()); // JMAP: not ported
}

#[test]
fn dates_and_message_ids() {
    assert_eq!(parse_date("Tue, 3 Nov 2026 14:05:09 +0000"), Some(1_793_714_709));
    assert_eq!(parse_date("3 Nov 2026 16:05 +0200 (CEST)"), Some(1_793_714_700));
    assert_eq!(parse_date("Thu, 1 Jan 1970 00:00:00 GMT"), Some(0));
    assert_eq!(parse_date("garbage"), None);
    assert_eq!(parse_message_ids("<a@b> junk <c@d >"), ["a@b", "c@d"]);
}
