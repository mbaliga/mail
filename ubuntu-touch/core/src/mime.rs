//! MIME codecs. Port of `mail-core/mime/Codecs.kt` (via the Swift MailKit port).
//! Decoding never fails and never drops a message: unknown charsets fall back to UTF-8,
//! malformed bytes become U+FFFD.

use fancy_regex::Regex;
use std::sync::OnceLock;

/// Decode bytes in a named charset; `None`/unknown names decode as UTF-8 (lossy).
pub fn decode_charset(bytes: &[u8], charset: Option<&str>) -> String {
    if bytes.is_empty() {
        return String::new();
    }
    let label = charset.map(|c| c.trim_matches(|ch: char| ch == ' ' || ch == '\t' || ch == '"' || ch == '\'')).unwrap_or("");
    // Mail labelled us-ascii/unknown-8bit is nearly always windows-1252 in practice.
    let lowered = label.to_ascii_lowercase();
    let enc = match lowered.as_str() {
        "" | "x-unknown" => encoding_rs::UTF_8,
        "unknown-8bit" | "us-ascii" | "ascii" | "ansi_x3.4-1968" => encoding_rs::WINDOWS_1252,
        other => encoding_rs::Encoding::for_label(other.as_bytes()).unwrap_or(encoding_rs::UTF_8),
    };
    enc.decode_without_bom_handling(bytes).0.into_owned()
}

/// Strict UTF-8 if it decodes cleanly, else windows-1252.
pub fn sniff(bytes: &[u8]) -> &'static str {
    if std::str::from_utf8(bytes).is_ok() { "utf-8" } else { "windows-1252" }
}

pub fn decode_transfer(bytes: &[u8], encoding: Option<&str>) -> Vec<u8> {
    match encoding.map(|e| e.trim().to_ascii_lowercase()).as_deref() {
        Some("base64") => decode_base64(bytes),
        Some("quoted-printable") => decode_quoted_printable(bytes),
        _ => bytes.to_vec(),
    }
}

fn b64_val(b: u8) -> Option<u32> {
    match b {
        b'A'..=b'Z' => Some((b - b'A') as u32),
        b'a'..=b'z' => Some((b - b'a') as u32 + 26),
        b'0'..=b'9' => Some((b - b'0') as u32 + 52),
        b'+' => Some(62),
        b'/' => Some(63),
        _ => None,
    }
}

/// Bit-accumulator decoder: junk and line breaks skipped, padding optional, padding mid-stream resets.
pub fn decode_base64(bytes: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len() * 3 / 4 + 3);
    let (mut acc, mut bits) = (0u32, 0u32);
    for &b in bytes {
        if let Some(v) = b64_val(b) {
            acc = ((acc << 6) | v) & 0xFF_FFFF;
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                out.push(((acc >> bits) & 0xFF) as u8);
            }
        } else if b == b'=' {
            acc = 0;
            bits = 0;
        }
    }
    out
}

fn hex(b: Option<&u8>) -> Option<u8> {
    match *b? {
        c @ b'0'..=b'9' => Some(c - b'0'),
        c @ b'A'..=b'F' => Some(c - b'A' + 10),
        c @ b'a'..=b'f' => Some(c - b'a' + 10),
        _ => None,
    }
}

pub fn decode_quoted_printable(bytes: &[u8]) -> Vec<u8> {
    let n = bytes.len();
    let mut out = Vec::with_capacity(n);
    let mut i = 0;
    while i < n {
        let b = bytes[i];
        if b != b'=' {
            out.push(b);
            i += 1;
            continue;
        }
        let mut j = i + 1;
        while j < n && (bytes[j] == b' ' || bytes[j] == b'\t') {
            j += 1;
        }
        if j < n && bytes[j] == b'\r' {
            j += 1;
            if j < n && bytes[j] == b'\n' {
                j += 1;
            }
            i = j;
            continue;
        }
        if j < n && bytes[j] == b'\n' {
            i = j + 1;
            continue;
        }
        if j == n {
            i = n; // "=" at the very end: soft break without newline
            continue;
        }
        match (hex(bytes.get(i + 1)), hex(bytes.get(i + 2))) {
            (Some(h), Some(l)) => {
                out.push(h * 16 + l);
                i += 3;
            }
            _ => {
                out.push(b); // stray '=': keep it, never drop text
                i += 1;
            }
        }
    }
    out
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn encode_base64(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 4 / 3 + 4);
    for chunk in bytes.chunks(3) {
        let n = (chunk[0] as u32) << 16 | (*chunk.get(1).unwrap_or(&0) as u32) << 8 | *chunk.get(2).unwrap_or(&0) as u32;
        out.push(B64[(n >> 18) as usize & 63] as char);
        out.push(B64[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { B64[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { B64[n as usize & 63] as char } else { '=' });
    }
    out
}

/// RFC 2045 base64 body: 76-character lines, CRLF.
pub fn encode_base64_lines(bytes: &[u8]) -> String {
    let enc = encode_base64(bytes);
    let mut out = String::new();
    for line in enc.as_bytes().chunks(76) {
        out.push_str(std::str::from_utf8(line).unwrap());
        out.push_str("\r\n");
    }
    out
}

/// Quoted-printable for text bodies: lines <= 76, trailing whitespace protected, CRLF endings.
pub fn encode_quoted_printable(text: &str) -> String {
    let normalised = text.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = normalised.split('\n').collect();
    let mut sb = String::new();
    for (li, line) in lines.iter().enumerate() {
        let mut col = 0;
        let bytes = line.as_bytes();
        for (bi, &b) in bytes.iter().enumerate() {
            let last = bi == bytes.len() - 1;
            let token = if b == b'=' {
                "=3D".to_string()
            } else if (b == b' ' || b == b'\t') && last {
                format!("={:02X}", b)
            } else if (33..=126).contains(&b) || b == b' ' || b == b'\t' {
                (b as char).to_string()
            } else {
                format!("={:02X}", b)
            };
            if col + token.len() > 75 {
                sb.push_str("=\r\n");
                col = 0;
            }
            col += token.len();
            sb.push_str(&token);
        }
        if li != lines.len() - 1 {
            sb.push_str("\r\n");
        }
    }
    sb
}

fn word_re() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"=\?([^?\s]+)\?([bBqQ])\?([^?\s]*)\?=").unwrap())
}

fn decode_q(text: &str) -> Vec<u8> {
    let u = text.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < u.len() {
        let c = u[i];
        if c == b'_' {
            out.push(0x20);
            i += 1;
        } else if c == b'=' && i + 2 < u.len() + 0 && hex(u.get(i + 1)).is_some() && hex(u.get(i + 2)).is_some() {
            out.push(hex(u.get(i + 1)).unwrap() * 16 + hex(u.get(i + 2)).unwrap());
            i += 3;
        } else {
            out.push(c);
            i += 1;
        }
    }
    out
}

/// RFC 2047 encoded-words. Whitespace between adjacent encoded words is dropped; a character split
/// across two same-charset words is joined before decoding; an RFC 2231 `*lang` tag is ignored.
pub fn decode_encoded_words(raw: &str) -> String {
    if !raw.contains("=?") {
        return raw.to_string();
    }
    let mut out = String::new();
    let mut last = 0usize;
    let mut pending_cs: Option<String> = None;
    let mut pending: Vec<u8> = Vec::new();
    let flush = |out: &mut String, cs: &mut Option<String>, pending: &mut Vec<u8>| {
        if let Some(c) = cs.take() {
            out.push_str(&decode_charset(pending, Some(&c)));
            pending.clear();
        }
    };
    for caps in word_re().captures_iter(raw).flatten() {
        let m = caps.get(0).unwrap();
        let between = &raw[last..m.start()];
        let charset = caps[1].split('*').next().unwrap_or("").to_string();
        let kind = caps[2].to_ascii_uppercase();
        let payload = &caps[3];
        let bytes = if kind == "B" { decode_base64(payload.as_bytes()) } else { decode_q(payload) };
        let adjacent = pending_cs.is_some() && between.trim().is_empty();
        if adjacent && pending_cs.as_deref().map(|p| p.eq_ignore_ascii_case(&charset)).unwrap_or(false) {
            pending.extend(bytes);
        } else {
            flush(&mut out, &mut pending_cs, &mut pending);
            if !adjacent {
                out.push_str(between);
            }
            pending_cs = Some(charset);
            pending.extend(bytes);
        }
        last = m.end();
    }
    flush(&mut out, &mut pending_cs, &mut pending);
    out.push_str(&raw[last..]);
    out
}

pub fn is_plain_ascii(text: &str) -> bool {
    text.chars().all(|c| (32..=126).contains(&(c as u32)))
}

/// Plain ASCII passes through; otherwise UTF-8 Base64 encoded-words of <= 75 chars, split on character boundaries.
pub fn encode_encoded_words(text: &str) -> String {
    if is_plain_ascii(text) {
        return text.to_string();
    }
    let mut words = Vec::new();
    let mut current: Vec<u8> = Vec::new();
    let mut buf = [0u8; 4];
    for ch in text.chars() {
        let chunk = ch.encode_utf8(&mut buf).as_bytes();
        if current.len() + chunk.len() > 45 {
            words.push(format!("=?UTF-8?B?{}?=", encode_base64(&current)));
            current.clear();
        }
        current.extend_from_slice(chunk);
    }
    if !current.is_empty() {
        words.push(format!("=?UTF-8?B?{}?=", encode_base64(&current)));
    }
    words.join("\r\n ")
}

/// A display name: quoted if it needs to be, encoded if it is not ASCII.
pub fn encode_phrase(name: &str) -> String {
    if !is_plain_ascii(name) {
        return encode_encoded_words(name);
    }
    if !name.chars().any(|c| "()<>[]:;@\\,.\"".contains(c)) {
        return name.to_string();
    }
    format!("\"{}\"", name.replace('\\', "\\\\").replace('"', "\\\""))
}
