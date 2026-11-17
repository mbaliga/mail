//! Vectors lifted from mail-core's MimeTest.kt (same as the Swift MailKit tests), so all three cores answer alike.
use mailcore::mime::*;

#[test]
fn encoded_words_decode_across_charsets_and_adjacent_words_join() {
    assert_eq!(decode_encoded_words("=?UTF-8?B?R3LDvMOfZQ==?= aus =?iso-8859-1?Q?K=F6ln?="), "Grüße aus Köln");
    assert_eq!(decode_encoded_words("=?UTF-8?B?R3LDvMOfZQ==?= =?iso-8859-1?Q?aus_K=F6ln?="), "Grüßeaus Köln");
    assert_eq!(decode_encoded_words("=?UTF-8?B?5pel?= =?UTF-8?B?5pys6Kqe?="), "日本語");
    assert_eq!(decode_encoded_words("plain text"), "plain text");
    assert_eq!(decode_encoded_words("Re: =?utf-8?q?=C3=9Cn=C3=AFcode?= tail"), "Re: Ünïcode tail");
}

#[test]
fn encoded_word_encode_round_trips_long_non_ascii() {
    let s = "Ünïcödé subject that is long enough to need splitting into several encoded words — 日本語も";
    let enc = encode_encoded_words(s);
    assert!(enc.split("\r\n ").all(|w| w.chars().count() <= 75));
    assert_eq!(decode_encoded_words(&enc.replace("\r\n ", " ")), s);
}

#[test]
fn phrase_quoting_and_ascii_passthrough() {
    assert_eq!(encode_encoded_words("Plain Subject"), "Plain Subject");
    assert_eq!(encode_phrase("Lovelace, Ada"), "\"Lovelace, Ada\"");
    assert_eq!(encode_phrase("Ada Lovelace"), "Ada Lovelace");
}

#[test]
fn base64_tolerates_line_breaks_junk_and_padding_mid_stream() {
    let dec = |s: &str| String::from_utf8_lossy(&decode_base64(s.as_bytes())).into_owned();
    assert_eq!(dec("SGVs\r\nbG8s IFdv\ncmxkIQ=="), "Hello, World!");
    assert_eq!(dec("QQ==Qg=="), "AB");
    assert_eq!(dec("!!!"), "");
}

#[test]
fn quoted_printable_keeps_stray_equals_and_decodes_soft_breaks() {
    let dec = |b: &[u8]| String::from_utf8_lossy(&decode_quoted_printable(b)).into_owned();
    assert_eq!(dec(b"a=b =\r\nc"), "a=b c");
    let original = format!("{} ünï =", "x".repeat(100));
    let qp = encode_quoted_printable(&original);
    assert!(qp.split("\r\n").all(|l| l.len() <= 76));
    assert_eq!(dec(qp.as_bytes()), original);
}

#[test]
fn base64_lines_are_76_wide_crlf() {
    let data = vec![0x41u8; 120];
    let out = encode_base64_lines(&data);
    let lines: Vec<&str> = out.split("\r\n").filter(|l| !l.is_empty()).collect();
    assert_eq!(lines[0].len(), 76);
    assert!(out.ends_with("\r\n"));
    assert_eq!(decode_base64(out.as_bytes()), data);
}

#[test]
fn windows_1252_smart_quotes_and_never_throwing() {
    assert_eq!(decode_charset(&[0x93, 0x68, 0x69, 0x94], Some("iso-8859-1")), "\u{201C}hi\u{201D}");
    assert_eq!(decode_charset(b"hi", Some("x-no-such-charset")), "hi");
    assert_eq!(decode_charset(&[], Some("utf-8")), "");
    assert!(decode_charset(&[0x68, 0xFF, 0x69], Some("utf-8")).contains('\u{FFFD}'));
    assert_eq!(sniff("é".as_bytes()), "utf-8");
    assert_eq!(sniff(&[0xE9]), "windows-1252");
}

#[test]
fn transfer_decode_dispatch() {
    assert_eq!(decode_transfer(b"SGk=", Some(" Base64 ")), b"Hi");
    assert_eq!(decode_transfer(b"H=69", Some("quoted-printable")), b"Hi");
    assert_eq!(decode_transfer(b"raw", None), b"raw");
}
