//! Vectors lifted from mail-core's ImapTest.kt (same as the Swift MailKit tests).
use mailcore::envelope::Envelope;
use mailcore::imap::*;

fn parse_all(s: &str) -> Vec<ImapResponse> {
    let mut buf = s.as_bytes().to_vec();
    let mut out = Vec::new();
    while !buf.is_empty() {
        let Some((r, used)) = parse_response(&buf).unwrap() else { break };
        out.push(r);
        buf.drain(..used);
    }
    assert!(buf.is_empty(), "unconsumed bytes left over");
    out
}

#[test]
fn status_responses_carry_codes_and_text() {
    let rs = parse_all("* OK [CAPABILITY IMAP4rev1 IDLE AUTH=XOAUTH2] Gimap ready\r\nY1 NO [TRYCREATE] No such mailbox\r\n");
    let ImapResponse::Status { tag, kind, code, text } = &rs[0] else { panic!("not a status") };
    assert!(tag.is_none());
    assert_eq!(kind, "OK");
    let code = code.as_ref().unwrap();
    assert_eq!(code.name, "CAPABILITY");
    assert_eq!(code.values.iter().map(|v| v.text().unwrap()).collect::<Vec<_>>(), ["IMAP4rev1", "IDLE", "AUTH=XOAUTH2"]);
    assert_eq!(text, "Gimap ready");
    let ImapResponse::Status { tag: t2, kind: k2, code: c2, .. } = &rs[1] else { panic!() };
    assert_eq!((t2.as_deref(), k2.as_str(), c2.as_ref().unwrap().name.as_str()), (Some("Y1"), "NO", "TRYCREATE"));
    assert!(!rs[1].is_ok());
}

fn value_for<'a>(items: &'a [ImapValue], key: &str) -> Option<&'a ImapValue> {
    let mut i = 0;
    while i + 1 < items.len() {
        if items[i].text().map(|t| t.eq_ignore_ascii_case(key)).unwrap_or(false) {
            return Some(&items[i + 1]);
        }
        i += 2;
    }
    None
}

#[test]
fn fetch_with_literal_section_and_envelope() {
    let header = "From: a@b.c\r\nSubject: Hi\r\n\r\n";
    let wire = format!(
        "* 12 FETCH (UID 345 FLAGS (\\Seen $Forwarded) INTERNALDATE \"03-Nov-2026 14:05:09 +0000\" RFC822.SIZE 4021 \
         ENVELOPE (\"Tue, 3 Nov 2026 14:05:09 +0000\" \"=?UTF-8?Q?Caf=C3=A9?=\" ((\"Ren\\\"ee\" NIL \"renee\" \"example.com\")) NIL NIL \
         ((NIL NIL \"me\" \"example.org\")) NIL NIL \"<parent@x>\" \"<abc@example.com>\") \
         BODY[HEADER.FIELDS (FROM SUBJECT)] {{{}}}\r\n{})\r\n",
        header.len(), header);
    let rs = parse_all(&wire);
    let ImapResponse::Data { number, keyword, values } = &rs[0] else { panic!("not data") };
    assert_eq!((*number, keyword.as_str()), (Some(12), "FETCH"));
    let items = values[0].items();
    assert_eq!(value_for(items, "UID").unwrap().long(), Some(345));
    let flags: Vec<String> = value_for(items, "FLAGS").unwrap().items().iter().filter_map(|v| v.text()).collect();
    assert_eq!(flags, ["\\Seen", "$Forwarded"]);
    assert_eq!(value_for(items, "RFC822.SIZE").unwrap().long(), Some(4021));
    let env = Envelope::parse(value_for(items, "ENVELOPE").unwrap()).unwrap();
    assert_eq!(env.subject, "Café");
    assert_eq!(env.from.len(), 1);
    assert_eq!(env.from[0].address, "renee@example.com");
    assert_eq!(env.from[0].name.as_deref(), Some("Ren\"ee"));
    assert_eq!(env.to[0].address, "me@example.org");
    assert_eq!(env.in_reply_to.as_deref(), Some("parent@x"));
    assert_eq!(env.message_id.as_deref(), Some("abc@example.com"));
    // 2026-11-03T14:05:09Z
    assert_eq!(env.date, Some(1_793_714_709));
    let section = value_for(items, "BODY[HEADER.FIELDS (FROM SUBJECT)]").unwrap().bytes().unwrap();
    assert_eq!(String::from_utf8(section).unwrap(), header);
}

#[test]
fn list_esearch_and_vanished_parse() {
    let rs = parse_all(
        "* LIST (\\HasNoChildren \\Sent) \"/\" \"[Gmail]/Sent Mail\"\r\n* ESEARCH (TAG \"Y5\") UID ALL 1:3,7,10:11\r\n* VANISHED (EARLIER) 41,43:45\r\n");
    let ImapResponse::Data { keyword, values, .. } = &rs[0] else { panic!() };
    assert_eq!(keyword, "LIST");
    assert_eq!(values[2].text().unwrap(), "[Gmail]/Sent Mail");
    let ImapResponse::Data { keyword, values, .. } = &rs[1] else { panic!() };
    assert_eq!(keyword, "ESEARCH");
    let last = values.last().unwrap().text().unwrap();
    assert_eq!(last, "1:3,7,10:11");
    assert_eq!(parse_sequence_set(&last, i64::MAX), [1, 2, 3, 7, 10, 11]);
    let ImapResponse::Data { keyword, .. } = &rs[2] else { panic!() };
    assert_eq!(keyword, "VANISHED");
}

#[test]
fn incomplete_input_asks_for_more_bytes_and_never_guesses() {
    let full = "* 1 FETCH (BODY[TEXT] {5}\r\nhello)\r\n";
    let bytes = full.as_bytes();
    for cut in [3, 10, bytes.len() - 5, bytes.len() - 1] {
        assert!(parse_response(&bytes[..cut]).unwrap().is_none(), "cut at {cut} must be incomplete");
    }
    let (r, used) = parse_response(bytes).unwrap().unwrap();
    assert_eq!(used, bytes.len());
    let ImapResponse::Data { keyword, values, .. } = r else { panic!() };
    assert_eq!(keyword, "FETCH");
    assert_eq!(values[0].items()[1].bytes().unwrap(), b"hello");
}

#[test]
fn continuation_and_pipelined_responses_consume_exactly() {
    let rs = parse_all("+ go ahead\r\n* 3 EXISTS\r\nA1 OK done\r\n");
    assert_eq!(rs.len(), 3);
    assert_eq!(rs[0], ImapResponse::Continuation("go ahead".into()));
    assert_eq!(rs[1], ImapResponse::Data { number: Some(3), keyword: "EXISTS".into(), values: vec![] });
    assert!(rs[2].is_ok());
}

#[test]
fn bad_literal_length_is_an_error_not_a_hang() {
    assert!(parse_response(b"* 1 FETCH (BODY[TEXT] {x}\r\n").is_err());
}

#[test]
fn modified_utf7_round_trips() {
    for s in ["Entwürfe", "日本語", "A&B", "Sent Items", "Корзина", "~peter/mail/台北/日本語"] {
        assert_eq!(utf7_decode(&utf7_encode(s)), s);
    }
    assert_eq!(utf7_encode("日本語"), "&ZeVnLIqe-");
    assert_eq!(utf7_encode("A&B"), "A&-B");
}

#[test]
fn sequence_sets_compress_and_chunk() {
    assert_eq!(sequence_set(&[10, 9, 7, 3, 2, 1]), "1:3,7,9:10");
    let all: Vec<i64> = (1..=1200).collect();
    assert_eq!(sequence_set_chunked(&all, 500).len(), 3);
    assert_eq!(sequence_set(&[]), "");
    assert!(parse_sequence_set(":", 10).is_empty());
    assert_eq!(parse_sequence_set("9:*", 11), [9, 10, 11]);
}

#[test]
fn quoting_and_literal_decision() {
    assert_eq!(quote("a\"b\\c"), "\"a\\\"b\\\\c\"");
    assert!(needs_literal("a\nb", true));
    assert!(needs_literal("é", false));
    assert!(!needs_literal("é", true));
}
