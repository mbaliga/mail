//! A small IMAP client: implicit-TLS sign-in with LOGIN, SELECT and a header-only envelope fetch.
//! Generic over `Read + Write` so the protocol logic is tested against scripted streams; the real
//! transport is rustls over `TcpStream`. Rules carried over from the Android core: TLS is never
//! silently downgraded (only implicit TLS is accepted) and credentials never reach a log or an error text.

use crate::envelope::Envelope;
use crate::imap::{parse_response, quote, utf7_encode, ImapResponse};
use std::fmt;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::Arc;
use std::time::Duration;

#[derive(Debug, PartialEq)]
pub enum ImapError {
    Closed,
    Io(String),
    Protocol(String),
    AuthenticationFailed(String),
    ServerRefused(String),
    InsecureTransportRefused,
    InvalidInput(&'static str),
}

impl fmt::Display for ImapError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ImapError::Closed => write!(f, "connection closed by the server"),
            ImapError::Io(m) => write!(f, "network error: {m}"),
            ImapError::Protocol(m) => write!(f, "unexpected server reply: {m}"),
            ImapError::AuthenticationFailed(m) => write!(f, "sign-in refused: {m}"),
            ImapError::ServerRefused(m) => write!(f, "server refused: {m}"),
            ImapError::InsecureTransportRefused => write!(f, "refused: only implicit TLS is accepted, never plaintext"),
            ImapError::InvalidInput(m) => write!(f, "invalid input: {m}"),
        }
    }
}
impl std::error::Error for ImapError {}

#[derive(Debug, Clone, PartialEq)]
pub struct Header {
    pub uid: i64,
    pub seen: bool,
    pub envelope: Envelope,
}

pub struct ImapClient<S: Read + Write> {
    stream: S,
    buf: Vec<u8>,
    tags: u32,
}

impl<S: Read + Write> ImapClient<S> {
    /// Wraps an already-secured stream and consumes the greeting (`* OK` / `* PREAUTH`).
    pub fn open(stream: S) -> Result<Self, ImapError> {
        let mut c = ImapClient { stream, buf: Vec::new(), tags: 0 };
        let greeting = c.next_response()?;
        if !greeting.is_ok() {
            return Err(ImapError::ServerRefused(format!("{greeting:?}")));
        }
        Ok(c)
    }

    fn next_response(&mut self) -> Result<ImapResponse, ImapError> {
        loop {
            match parse_response(&self.buf).map_err(|e| ImapError::Protocol(e.0))? {
                Some((r, used)) => {
                    self.buf.drain(..used);
                    return Ok(r);
                }
                None => {
                    let mut chunk = [0u8; 16 * 1024];
                    let n = self.stream.read(&mut chunk).map_err(|e| ImapError::Io(e.to_string()))?;
                    if n == 0 {
                        return Err(ImapError::Closed);
                    }
                    self.buf.extend_from_slice(&chunk[..n]);
                    if self.buf.len() > 64 * 1024 * 1024 {
                        return Err(ImapError::Protocol("response too large".into()));
                    }
                }
            }
        }
    }

    /// Sends one command; returns every untagged response up to its tagged completion.
    pub fn command(&mut self, text: &str) -> Result<(Vec<ImapResponse>, ImapResponse), ImapError> {
        self.tags += 1;
        let tag = format!("A{}", self.tags);
        let line = format!("{tag} {text}\r\n");
        self.stream.write_all(line.as_bytes()).and_then(|_| self.stream.flush()).map_err(|e| ImapError::Io(e.to_string()))?;
        let mut untagged = Vec::new();
        loop {
            let r = self.next_response()?;
            if matches!(&r, ImapResponse::Status { tag: Some(t), .. } if *t == tag) {
                return Ok((untagged, r));
            }
            untagged.push(r);
        }
    }

    pub fn login(&mut self, user: &str, password: &str) -> Result<(), ImapError> {
        if user.contains(['\r', '\n']) || password.contains(['\r', '\n']) {
            return Err(ImapError::InvalidInput("credentials may not contain line breaks"));
        }
        let (_, status) = self.command(&format!("LOGIN {} {}", quote(user), quote(password)))?;
        match status {
            ImapResponse::Status { kind, text, .. } if kind == "OK" => {
                let _ = text;
                Ok(())
            }
            ImapResponse::Status { text, .. } => Err(ImapError::AuthenticationFailed(text)),
            _ => Err(ImapError::AuthenticationFailed(String::new())),
        }
    }

    /// Selects a mailbox read-only and returns the EXISTS count.
    pub fn examine(&mut self, mailbox: &str) -> Result<i64, ImapError> {
        let (untagged, status) = self.command(&format!("EXAMINE {}", quote(&utf7_encode(mailbox))))?;
        if !status.is_ok() {
            return Err(ImapError::ServerRefused(format!("{status:?}")));
        }
        for r in &untagged {
            if let ImapResponse::Data { number: Some(n), keyword, .. } = r {
                if keyword == "EXISTS" {
                    return Ok(*n);
                }
            }
        }
        Ok(0)
    }

    /// The newest `limit` messages' envelopes, newest first. Read-only (EXAMINE + ENVELOPE, no body fetch).
    pub fn recent_headers(&mut self, exists: i64, limit: i64) -> Result<Vec<Header>, ImapError> {
        if exists <= 0 {
            return Ok(Vec::new());
        }
        let first = (exists - limit + 1).max(1);
        let (untagged, status) = self.command(&format!("FETCH {first}:{exists} (UID FLAGS ENVELOPE)"))?;
        if !status.is_ok() {
            return Err(ImapError::ServerRefused(format!("{status:?}")));
        }
        let mut out = Vec::new();
        for r in &untagged {
            let ImapResponse::Data { keyword, values, .. } = r else { continue };
            if keyword != "FETCH" {
                continue;
            }
            let items = values.first().map(|v| v.items()).unwrap_or(&[]);
            let (mut uid, mut seen, mut env) = (None, false, None);
            let mut i = 0;
            while i + 1 < items.len() {
                match items[i].text().map(|t| t.to_uppercase()).as_deref() {
                    Some("UID") => uid = items[i + 1].long(),
                    Some("FLAGS") => seen = items[i + 1].items().iter().any(|f| f.text().map(|t| t.eq_ignore_ascii_case("\\seen")).unwrap_or(false)),
                    Some("ENVELOPE") => env = Envelope::parse(&items[i + 1]),
                    _ => {}
                }
                i += 2;
            }
            if let (Some(uid), Some(envelope)) = (uid, env) {
                out.push(Header { uid, seen, envelope });
            }
        }
        out.sort_by(|a, b| b.uid.cmp(&a.uid));
        Ok(out)
    }

    pub fn logout(&mut self) {
        let _ = self.command("LOGOUT");
    }
}

/// Opens implicit TLS (rustls, Mozilla roots) to `host:port`. Anything but implicit TLS is refused.
pub fn connect_tls(host: &str, port: u16, security: &str) -> Result<ImapClient<rustls::StreamOwned<rustls::ClientConnection, TcpStream>>, ImapError> {
    if security != "sslTls" {
        return Err(ImapError::InsecureTransportRefused);
    }
    let roots = rustls::RootCertStore { roots: webpki_roots::TLS_SERVER_ROOTS.to_vec() };
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .map_err(|e| ImapError::Io(e.to_string()))?
        .with_root_certificates(roots)
        .with_no_client_auth();
    let name = rustls::pki_types::ServerName::try_from(host.to_string()).map_err(|_| ImapError::InvalidInput("bad host name"))?;
    let conn = rustls::ClientConnection::new(Arc::new(config), name).map_err(|e| ImapError::Io(e.to_string()))?;
    let tcp = TcpStream::connect((host, port)).map_err(|e| ImapError::Io(e.to_string()))?;
    tcp.set_read_timeout(Some(Duration::from_secs(30))).ok();
    tcp.set_write_timeout(Some(Duration::from_secs(30))).ok();
    ImapClient::open(rustls::StreamOwned::new(conn, tcp))
}

/// One-shot: connect, sign in, read the newest `limit` envelopes of INBOX, sign out.
pub fn fetch_inbox(host: &str, port: u16, security: &str, user: &str, password: &str, limit: i64) -> Result<Vec<Header>, ImapError> {
    let mut c = connect_tls(host, port, security)?;
    c.login(user, password)?;
    let exists = c.examine("INBOX")?;
    let headers = c.recent_headers(exists, limit)?;
    c.logout();
    Ok(headers)
}
