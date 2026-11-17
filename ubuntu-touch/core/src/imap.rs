//! IMAP response grammar, mailbox-name and sequence-set helpers.
//! Port of `mail-core/imap/ImapProtocol.kt` (via the Swift MailKit port): the parser is
//! buffer-based, so `parse` returns `Ok(None)` while a response (or its literal) is still arriving.

#[derive(Debug, Clone, PartialEq)]
pub enum ImapValue {
    Atom(String),
    Str(Vec<u8>),
    Null,
    List(Vec<ImapValue>),
}

impl ImapValue {
    /// Atoms and strings read as text; NIL and lists read as None.
    pub fn text(&self) -> Option<String> {
        match self {
            ImapValue::Atom(t) => Some(t.clone()),
            ImapValue::Str(b) => Some(String::from_utf8_lossy(b).into_owned()),
            _ => None,
        }
    }
    pub fn long(&self) -> Option<i64> {
        self.text().and_then(|t| t.parse().ok())
    }
    pub fn items(&self) -> &[ImapValue] {
        match self {
            ImapValue::List(l) => l,
            _ => &[],
        }
    }
    pub fn bytes(&self) -> Option<Vec<u8>> {
        match self {
            ImapValue::Str(b) => Some(b.clone()),
            ImapValue::Atom(t) => Some(t.clone().into_bytes()),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ResponseCode {
    pub name: String,
    pub values: Vec<ImapValue>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ImapResponse {
    Continuation(String),
    Status { tag: Option<String>, kind: String, code: Option<ResponseCode>, text: String },
    Data { number: Option<i64>, keyword: String, values: Vec<ImapValue> },
}

impl ImapResponse {
    pub fn is_ok(&self) -> bool {
        matches!(self, ImapResponse::Status { kind, .. } if kind == "OK" || kind == "PREAUTH")
    }
}

#[derive(Debug, PartialEq)]
pub struct ParseError(pub String);

enum Fail {
    Incomplete,
    Bad(String),
}
type R<T> = Result<T, Fail>;

struct Cursor<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Cursor<'a> {
    fn peek(&self) -> R<u8> {
        self.b.get(self.i).copied().ok_or(Fail::Incomplete)
    }
    fn read(&mut self) -> R<u8> {
        let c = self.peek()?;
        self.i += 1;
        Ok(c)
    }
}

/// Returns the next complete response and the number of bytes it used, or `None` if more bytes are needed.
pub fn parse_response(buffer: &[u8]) -> Result<Option<(ImapResponse, usize)>, ParseError> {
    let mut c = Cursor { b: buffer, i: 0 };
    match parse_one(&mut c) {
        Ok(r) => Ok(Some((r, c.i))),
        Err(Fail::Incomplete) => Ok(None),
        Err(Fail::Bad(m)) => Err(ParseError(m)),
    }
}

fn parse_one(c: &mut Cursor) -> R<ImapResponse> {
    if c.peek()? == b'+' {
        c.read()?;
        if c.peek()? == b' ' {
            c.read()?;
        }
        return Ok(ImapResponse::Continuation(rest_of_line(c)?));
    }
    let tag = token(c)?;
    skip_spaces(c)?;
    if tag == "*" {
        let word = token(c)?;
        if let Ok(number) = word.parse::<i64>() {
            skip_spaces(c)?;
            let keyword = token(c)?.to_uppercase();
            return Ok(ImapResponse::Data { number: Some(number), keyword, values: values_to_eol(c)? });
        }
        let upper = word.to_uppercase();
        return match upper.as_str() {
            "OK" | "NO" | "BAD" | "BYE" | "PREAUTH" => status(c, None, upper),
            _ => Ok(ImapResponse::Data { number: None, keyword: upper, values: values_to_eol(c)? }),
        };
    }
    let kind = token(c)?.to_uppercase();
    status(c, Some(tag), kind)
}

fn status(c: &mut Cursor, tag: Option<String>, kind: String) -> R<ImapResponse> {
    skip_spaces(c)?;
    let mut code = None;
    if !at_eol(c)? && c.peek()? == b'[' {
        c.read()?;
        let mut content = Vec::new();
        let mut depth = 1;
        loop {
            let ch = c.read()?;
            if ch == b'[' {
                depth += 1;
            }
            if ch == b']' {
                depth -= 1;
                if depth == 0 {
                    break;
                }
            }
            if ch == b'\n' {
                break;
            }
            content.push(ch);
        }
        code = Some(parse_code(&String::from_utf8_lossy(&content))?);
        skip_spaces(c)?;
    }
    Ok(ImapResponse::Status { tag, kind, code, text: rest_of_line(c)? })
}

fn parse_code(content: &str) -> R<ResponseCode> {
    let (name, rest) = match content.find(' ') {
        Some(sp) => (content[..sp].to_uppercase(), &content[sp + 1..]),
        None => (content.to_uppercase(), ""),
    };
    let mut values = Vec::new();
    if !rest.is_empty() {
        let line = format!("{rest}\r\n");
        let mut sub = Cursor { b: line.as_bytes(), i: 0 };
        values = values_to_eol(&mut sub)?;
    }
    Ok(ResponseCode { name, values })
}

fn values_to_eol(c: &mut Cursor) -> R<Vec<ImapValue>> {
    let mut out = Vec::new();
    loop {
        skip_spaces(c)?;
        if at_eol(c)? {
            consume_eol(c)?;
            return Ok(out);
        }
        out.push(value(c)?);
    }
}

fn value(c: &mut Cursor) -> R<ImapValue> {
    match c.peek()? {
        b'(' => {
            c.read()?;
            let mut items = Vec::new();
            loop {
                skip_spaces(c)?;
                let ch = c.peek()?;
                if ch == b')' {
                    c.read()?;
                    break;
                }
                if ch == b'\r' || ch == b'\n' {
                    break; // unterminated list at end of line: tolerate
                }
                items.push(value(c)?);
            }
            Ok(ImapValue::List(items))
        }
        b'"' => Ok(ImapValue::Str(quoted(c)?)),
        b'{' => Ok(ImapValue::Str(literal(c)?)),
        b'~' => {
            c.read()?;
            if c.peek()? == b'{' {
                return Ok(ImapValue::Str(literal(c)?));
            }
            Ok(ImapValue::Atom(format!("~{}", atom_tail(c)?)))
        }
        _ => {
            let a = atom_tail(c)?;
            Ok(if a.eq_ignore_ascii_case("NIL") { ImapValue::Null } else { ImapValue::Atom(a) })
        }
    }
}

fn quoted(c: &mut Cursor) -> R<Vec<u8>> {
    c.read()?;
    let mut out = Vec::new();
    loop {
        match c.read()? {
            b'"' => return Ok(out),
            b'\\' => out.push(c.read()?),
            b'\r' | b'\n' => return Ok(out), // broken server; never hang
            ch => out.push(ch),
        }
    }
}

fn literal(c: &mut Cursor) -> R<Vec<u8>> {
    c.read()?;
    let mut digits = String::new();
    loop {
        let ch = c.read()?;
        if ch == b'}' {
            break;
        }
        if ch == b'+' || ch == b'-' {
            continue;
        }
        digits.push(ch as char);
    }
    let n: usize = digits.parse().map_err(|_| Fail::Bad(format!("Bad literal length: {digits}")))?;
    if c.peek()? == b'\r' {
        c.read()?;
    }
    if c.peek()? == b'\n' {
        c.read()?;
    }
    if c.i + n > c.b.len() {
        return Err(Fail::Incomplete);
    }
    let out = c.b[c.i..c.i + n].to_vec();
    c.i += n;
    Ok(out)
}

fn atom_tail(c: &mut Cursor) -> R<String> {
    let mut sb = Vec::new();
    let mut depth = 0;
    loop {
        let ch = c.peek()?;
        if depth == 0 && matches!(ch, b' ' | b'(' | b')' | b'\r' | b'\n') {
            break;
        }
        if depth > 0 && (ch == b'\r' || ch == b'\n') {
            break;
        }
        if ch == b'[' {
            depth += 1;
        }
        if ch == b']' && depth > 0 {
            depth -= 1;
        }
        sb.push(c.read()?);
    }
    Ok(String::from_utf8_lossy(&sb).into_owned())
}

fn token(c: &mut Cursor) -> R<String> {
    let mut sb = Vec::new();
    loop {
        let ch = c.peek()?;
        if matches!(ch, b' ' | b'\r' | b'\n') {
            break;
        }
        if ch == b'[' && !sb.is_empty() {
            break;
        }
        sb.push(c.read()?);
    }
    Ok(String::from_utf8_lossy(&sb).into_owned())
}

fn rest_of_line(c: &mut Cursor) -> R<String> {
    let mut out = Vec::new();
    loop {
        let ch = c.read()?;
        if ch == b'\n' {
            break;
        }
        if ch != b'\r' {
            out.push(ch);
        }
    }
    Ok(String::from_utf8_lossy(&out).into_owned())
}

fn skip_spaces(c: &mut Cursor) -> R<()> {
    while c.peek()? == b' ' {
        c.read()?;
    }
    Ok(())
}
fn at_eol(c: &mut Cursor) -> R<bool> {
    let ch = c.peek()?;
    Ok(ch == b'\r' || ch == b'\n')
}
fn consume_eol(c: &mut Cursor) -> R<()> {
    if c.peek()? == b'\r' {
        c.read()?;
    }
    if c.peek()? == b'\n' {
        c.read()?;
    }
    Ok(())
}

// ---- modified UTF-7 (RFC 3501 5.1.3) ----

const U7: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,";

pub fn utf7_encode(s: &str) -> String {
    let units: Vec<u16> = s.encode_utf16().collect();
    let printable = |u: u16| (0x20..=0x7e).contains(&u);
    let mut sb = String::new();
    let mut i = 0;
    while i < units.len() {
        let u = units[i];
        if printable(u) {
            if u == 0x26 {
                sb.push_str("&-");
            } else {
                sb.push(u as u8 as char);
            }
            i += 1;
            continue;
        }
        let start = i;
        while i < units.len() && !printable(units[i]) {
            i += 1;
        }
        sb.push('&');
        let (mut bits, mut acc) = (0u32, 0u32);
        for w in &units[start..i] {
            for byte in [(*w >> 8) as u32, (*w & 0xFF) as u32] {
                acc = (acc << 8) | byte;
                bits += 8;
                while bits >= 6 {
                    bits -= 6;
                    sb.push(U7[((acc >> bits) & 0x3F) as usize] as char);
                }
                acc &= (1 << bits) - 1;
            }
        }
        if bits > 0 {
            sb.push(U7[((acc << (6 - bits)) & 0x3F) as usize] as char);
        }
        sb.push('-');
    }
    sb
}

pub fn utf7_decode(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let chars: Vec<char> = s.chars().collect();
    let mut sb = String::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c != '&' {
            sb.push(c);
            i += 1;
            continue;
        }
        let end = chars[i + 1..].iter().position(|&x| x == '-').map(|p| p + i + 1).unwrap_or(chars.len());
        if end == i + 1 {
            sb.push('&');
            i = end + 1;
            continue;
        }
        let mut bytes = Vec::new();
        let (mut bits, mut acc) = (0u32, 0u32);
        for ch in &chars[i + 1..end] {
            let Some(v) = U7.iter().position(|&a| a as char == *ch) else { continue };
            acc = (acc << 6) | v as u32;
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                bytes.push(((acc >> bits) & 0xFF) as u8);
                acc &= (1 << bits) - 1;
            }
        }
        let units: Vec<u16> = bytes.chunks_exact(2).map(|p| (p[0] as u16) << 8 | p[1] as u16).collect();
        sb.push_str(&String::from_utf16_lossy(&units));
        i = end + 1;
    }
    sb
}

// ---- sequence sets ----

/// `[1,2,3,7,9,10]` -> `"1:3,7,9:10"`.
pub fn sequence_set(uids: &[i64]) -> String {
    let mut sorted: Vec<i64> = uids.to_vec();
    sorted.sort_unstable();
    sorted.dedup();
    let Some(&first) = sorted.first() else { return String::new() };
    let (mut start, mut prev) = (first, first);
    let mut parts = Vec::new();
    let mut emit = |s: i64, p: i64| parts.push(if s == p { s.to_string() } else { format!("{s}:{p}") });
    for &u in &sorted[1..] {
        if u == prev + 1 {
            prev = u;
            continue;
        }
        emit(start, prev);
        start = u;
        prev = u;
    }
    emit(start, prev);
    parts.join(",")
}

pub fn sequence_set_chunked(uids: &[i64], max_per_chunk: usize) -> Vec<String> {
    let mut sorted: Vec<i64> = uids.to_vec();
    sorted.sort_unstable();
    sorted.dedup();
    sorted.chunks(max_per_chunk.max(1)).map(sequence_set).collect()
}

/// Parse `1:3,7,9:*` against `max_uid` for `*`. Absurd ranges (> 1,000,000) yield nothing.
pub fn parse_sequence_set(set: &str, max_uid: i64) -> Vec<i64> {
    let mut out = Vec::new();
    for part in set.split(',') {
        let mut it = part.splitn(2, ':');
        let a = it.next().unwrap_or("");
        let b = it.next().unwrap_or(a);
        let num = |s: &str| if s == "*" { Some(max_uid) } else { s.parse::<i64>().ok() };
        let (Some(lo), Some(hi)) = (num(a), num(b)) else { continue };
        let (x, y) = if lo <= hi { (lo, hi) } else { (hi, lo) };
        if y - x > 1_000_000 {
            continue;
        }
        out.extend(x..=y);
    }
    out
}

pub fn quote(s: &str) -> String {
    format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\""))
}

pub fn needs_literal(s: &str, utf8: bool) -> bool {
    s.contains('\r') || s.contains('\n') || (!utf8 && s.chars().any(|c| c as u32 > 0x7e))
}
