//! The IMAP client against scripted in-memory streams (no network).
use mailcore::client::*;
use std::io::{Cursor, Read, Write};

/// Replays canned server bytes; records what the client wrote.
struct Script {
    input: Cursor<Vec<u8>>,
    wrote: Vec<u8>,
}
impl Read for Script {
    fn read(&mut self, b: &mut [u8]) -> std::io::Result<usize> {
        // Dribble 7 bytes at a time to exercise the incomplete-response path.
        let n = b.len().min(7);
        self.input.read(&mut b[..n])
    }
}
impl Write for Script {
    fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
        self.wrote.extend_from_slice(b);
        Ok(b.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}
fn script(server: &str) -> Script {
    Script { input: Cursor::new(server.as_bytes().to_vec()), wrote: vec![] }
}

#[test]
fn full_session_login_examine_fetch() {
    let server = "* OK IMAP ready\r\n\
        A1 OK LOGIN completed\r\n\
        * 2 EXISTS\r\n* OK [UIDVALIDITY 7] x\r\nA2 OK [READ-ONLY] done\r\n\
        * 1 FETCH (UID 10 FLAGS () ENVELOPE (\"Mon, 2 Nov 2026 10:00:00 +0000\" \"first\" ((\"A\" NIL \"a\" \"x.org\")) NIL NIL NIL NIL NIL NIL \"<m1@x>\"))\r\n\
        * 2 FETCH (UID 11 FLAGS (\\Seen) ENVELOPE (\"Tue, 3 Nov 2026 10:00:00 +0000\" \"second\" ((NIL NIL \"b\" \"y.org\")) NIL NIL NIL NIL NIL NIL \"<m2@x>\"))\r\n\
        A3 OK FETCH done\r\nA4 OK bye\r\n";
    let mut c = ImapClient::open(script(server)).unwrap();
    c.login("ada@x.org", "p\"w").unwrap();
    let n = c.examine("INBOX").unwrap();
    assert_eq!(n, 2);
    let hs = c.recent_headers(n, 50).unwrap();
    assert_eq!(hs.iter().map(|h| h.uid).collect::<Vec<_>>(), [11, 10]); // newest first
    assert!(hs[0].seen && !hs[1].seen);
    assert_eq!(hs[0].envelope.subject, "second");
    assert_eq!(hs[1].envelope.from[0].display_name(), "A");
    c.logout();
}

#[test]
fn login_failure_is_reported_without_echoing_the_password() {
    let mut c = ImapClient::open(script("* OK hi\r\nA1 NO [AUTHENTICATIONFAILED] Invalid credentials\r\n")).unwrap();
    let err = c.login("ada", "hunter2-secret").unwrap_err();
    assert!(matches!(err, ImapError::AuthenticationFailed(_)));
    assert!(!err.to_string().contains("hunter2"));
}

#[test]
fn greeting_refusal_and_early_close() {
    assert!(matches!(ImapClient::open(script("* BYE go away\r\n")), Err(ImapError::ServerRefused(_))));
    assert!(matches!(ImapClient::open(script("* OK hi\r\n")).unwrap().command("NOOP"), Err(ImapError::Closed)));
}

#[test]
fn credentials_with_line_breaks_are_refused_before_anything_is_sent() {
    let mut c = ImapClient::open(script("* OK hi\r\n")).unwrap();
    assert!(matches!(c.login("a\r\nA9 LOGOUT", "x"), Err(ImapError::InvalidInput(_))));
}

#[test]
fn plaintext_and_starttls_are_refused_never_downgraded() {
    assert!(matches!(connect_tls("example.org", 143, "none"), Err(ImapError::InsecureTransportRefused)));
    assert!(matches!(connect_tls("127.0.0.1", 1143, "startTls"), Err(ImapError::InsecureTransportRefused)));
}

#[test]
fn ffi_round_trip() {
    use mailcore::ffi::*;
    use std::ffi::{CStr, CString};
    let e = CString::new("ada@icloud.com").unwrap();
    let p = mailcore_provider_for_email(e.as_ptr());
    let s = unsafe { CStr::from_ptr(p) }.to_str().unwrap().to_string();
    unsafe { mailcore_free(p) };
    let v: serde_json::Value = serde_json::from_str(&s).unwrap();
    assert_eq!(v["provider"]["id"], "icloud");
    assert_eq!(v["provider"]["signInSupported"], true);
    // fetch refuses plaintext through the C API too, and the error never contains the password
    let (h, sec, u, pw) = (CString::new("x").unwrap(), CString::new("none").unwrap(), CString::new("u").unwrap(), CString::new("sekrit").unwrap());
    let p = mailcore_fetch_inbox(h.as_ptr(), 143, sec.as_ptr(), u.as_ptr(), pw.as_ptr(), 10);
    let s = unsafe { CStr::from_ptr(p) }.to_str().unwrap().to_string();
    unsafe { mailcore_free(p) };
    assert!(s.contains("\"ok\":false") && !s.contains("sekrit"));
}
