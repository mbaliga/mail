//! ENVELOPE decoding, message ids and RFC 5322 dates. Port of `mail-core/imap/FetchModel.kt`.

use crate::imap::ImapValue;
use crate::mime::decode_encoded_words;

#[derive(Debug, Clone, PartialEq)]
pub struct EmailAddress {
    pub address: String,
    pub name: Option<String>,
}

impl EmailAddress {
    /// What a person reads: the display name when there is a meaningful one, else the address.
    pub fn display_name(&self) -> String {
        match &self.name {
            Some(n) if !n.trim().is_empty() && !n.eq_ignore_ascii_case(&self.address) => n.clone(),
            _ => self.address.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Envelope {
    /// Unix seconds (UTC), if the Date header parsed.
    pub date: Option<i64>,
    pub subject: String,
    pub from: Vec<EmailAddress>,
    pub sender: Vec<EmailAddress>,
    pub reply_to: Vec<EmailAddress>,
    pub to: Vec<EmailAddress>,
    pub cc: Vec<EmailAddress>,
    pub bcc: Vec<EmailAddress>,
    pub in_reply_to: Option<String>,
    pub message_id: Option<String>,
}

impl Envelope {
    pub fn parse(v: &ImapValue) -> Option<Envelope> {
        let f = v.items();
        if f.len() < 10 {
            return None;
        }
        let subject = f[1]
            .text()
            .map(|s| {
                decode_encoded_words(&s)
                    .split(|c| c == '\r' || c == '\n' || c == '\t')
                    .filter(|p| !p.is_empty())
                    .collect::<Vec<_>>()
                    .join(" ")
                    .trim()
                    .to_string()
            })
            .unwrap_or_default();
        Some(Envelope {
            date: f[0].text().and_then(|d| parse_date(&d)),
            subject,
            from: addresses(&f[2]),
            sender: addresses(&f[3]),
            reply_to: addresses(&f[4]),
            to: addresses(&f[5]),
            cc: addresses(&f[6]),
            bcc: addresses(&f[7]),
            in_reply_to: f[8].text().and_then(|s| parse_message_ids(&s).into_iter().next()),
            message_id: f[9].text().and_then(|s| parse_message_ids(&s).into_iter().next()),
        })
    }
}

/// `((name adl mailbox host) ...)`; a NIL host marks an RFC 2822 group start/end, which is skipped.
fn addresses(v: &ImapValue) -> Vec<EmailAddress> {
    v.items()
        .iter()
        .filter_map(|a| {
            let p = a.items();
            if p.len() < 4 {
                return None;
            }
            let (mailbox, host) = (p[2].text()?, p[3].text()?);
            let name = p[0].text().map(|n| decode_encoded_words(&n).trim().to_string()).filter(|n| !n.is_empty());
            Some(EmailAddress { address: format!("{mailbox}@{host}"), name })
        })
        .collect()
}

/// Every `<id>` in a header value, brackets removed.
pub fn parse_message_ids(raw: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut rest = raw;
    while let Some(open) = rest.find('<') {
        let Some(close) = rest[open..].find('>').map(|c| c + open) else { break };
        let id = rest[open + 1..close].trim();
        if !id.is_empty() {
            out.push(id.to_string());
        }
        rest = &rest[close + 1..];
    }
    out
}

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// RFC 5322 dates to Unix seconds. Tolerates a missing weekday, a trailing `(UTC)` comment,
/// no seconds, two-digit years, numeric zones and the common zone names.
pub fn parse_date(raw: &str) -> Option<i64> {
    let mut s = raw.trim().to_string();
    if let Some(p) = s.find('(') {
        s.truncate(p);
    }
    let s = s.trim();
    let s = match s.find(',') {
        Some(c) if s[..c].chars().all(|ch| ch.is_ascii_alphabetic()) => &s[c + 1..],
        _ => s,
    };
    let parts: Vec<&str> = s.split_whitespace().collect();
    if parts.len() < 4 {
        return None;
    }
    let day: i64 = parts[0].parse().ok()?;
    const MONTHS: [&str; 12] = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
    let mon = parts[1].to_ascii_lowercase();
    let month = MONTHS.iter().position(|m| mon.starts_with(m))? as i64 + 1;
    let mut year: i64 = parts[2].parse().ok()?;
    if parts[2].len() <= 2 {
        year += if year < 50 { 2000 } else { 1900 };
    }
    let t: Vec<i64> = parts[3].split(':').map(|x| x.parse().ok()).collect::<Option<Vec<_>>>()?;
    if t.len() < 2 {
        return None;
    }
    let (h, mi, sec) = (t[0], t[1], *t.get(2).unwrap_or(&0));
    let zone = parts.get(4).copied().unwrap_or("+0000");
    let offset = match zone {
        "UT" | "UTC" | "GMT" | "Z" => 0,
        "EST" => -5 * 3600,
        "EDT" => -4 * 3600,
        "CST" => -6 * 3600,
        "CDT" => -5 * 3600,
        "MST" => -7 * 3600,
        "MDT" => -6 * 3600,
        "PST" => -8 * 3600,
        "PDT" => -7 * 3600,
        z if (z.starts_with('+') || z.starts_with('-')) && z.len() == 5 => {
            let sign = if z.starts_with('-') { -1 } else { 1 };
            let hh: i64 = z[1..3].parse().ok()?;
            let mm: i64 = z[3..5].parse().ok()?;
            sign * (hh * 3600 + mm * 60)
        }
        _ => 0,
    };
    Some(days_from_civil(year, month, day) * 86400 + h * 3600 + mi * 60 + sec - offset)
}
