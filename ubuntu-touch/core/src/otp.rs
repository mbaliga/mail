//! One-time-code / magic-link finder. Port of `OtpExtractor` (mail-core/intel/Otp.kt).
//! Offsets are measured in characters (not bytes) so distances match the Kotlin/Swift cores.

use fancy_regex::Regex;
use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq)]
pub struct OtpFinding {
    pub code: Option<String>,
    pub is_magic_link: bool,
    pub service: Option<String>,
    /// Minutes the sender says it is valid for; None when unstated (the UI assumes 15).
    pub valid_minutes: Option<u32>,
    pub confidence: f32,
}

const KEYWORDS: &[&str] = &[
    "verification code", "verify", "one-time", "one time", "otp", "passcode", "pass code", "security code", "login code",
    "log-in code", "sign-in code", "sign in code", "authentication code", "confirmation code", "access code", "2fa", "two-factor",
    "two factor", "2-step", "your code", "the code", "code is", "code:", "pin", "auth code", "temporary code",
    "bestätigungscode", "sicherheitscode", "verifizierungscode", "code de vérification", "code de sécurité", "code de confirmation",
    "código de verificación", "código de seguridad", "código", "codice di verifica", "codice", "verificatiecode",
    "kod weryfikacyjny", "код подтверждения", "код", "验证码", "認証コード", "確認コード", "인증번호", "인증 코드", "doğrulama kodu",
];

fn magic_re() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(r"(?i)(magic link|sign[- ]?in link|log[- ]?in link|click (the )?(link|button) (below )?to (sign|log) ?in|confirm your (sign[- ]?in|login)|one-click sign[- ]?in)").unwrap()
    })
}
fn candidate_re() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(r"(?<![A-Za-z0-9_$€£¥₹/.:#-])(G-)?([0-9]{3,4}[ -][0-9]{3,4}|[0-9]{4,8}|[A-Z0-9]{6,8})(?![A-Za-z0-9_%/:-]|\.[0-9])").unwrap()
    })
}
fn validity_re() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"(?i)(?:valid|expires?|expiring|good)\s+(?:for|in|within)\s+([0-9]{1,3})\s*(min|minute|minutes|mins|hour|hours|h)\b").unwrap())
}

fn plausible(token: &str, before: &str) -> bool {
    let len = token.chars().count();
    if !(4..=8).contains(&len) {
        return false;
    }
    let digits = token.chars().filter(|c| c.is_ascii_digit()).count();
    if token.chars().any(|c| c.is_alphabetic()) && (digits == 0 || token.chars().any(|c| c.is_lowercase())) {
        return false; // alphanumeric codes must mix letters and digits and be upper-case
    }
    if digits == len {
        let n: i64 = token.parse().unwrap_or(0);
        if len == 4 && (1900..=2099).contains(&n) {
            return false; // a year
        }
        let first = token.chars().next().unwrap();
        if token.chars().all(|c| c == first) && len < 6 {
            return false; // 0000, 1111
        }
    }
    let lower = before.to_lowercase();
    !["order", "invoice", "#", "tel", "phone", "ref", "account ending", "card ending"].iter().any(|b| lower.contains(b))
}

pub fn find_otp(subject: &str, text: &str, from_name: Option<&str>) -> Option<OtpFinding> {
    let body: String = text.chars().take(4000).collect();
    let hay: Vec<char> = format!("{subject}\n{body}").chars().collect();
    let hay_str: String = hay.iter().collect();
    let lower: Vec<char> = hay_str.to_lowercase().chars().collect();
    // Byte offset -> char index map for the regex matches.
    let mut byte_to_char = std::collections::HashMap::new();
    for (ci, (bi, _)) in hay_str.char_indices().enumerate() {
        byte_to_char.insert(bi, ci);
    }
    byte_to_char.insert(hay_str.len(), hay.len());
    let ci = |b: usize| *byte_to_char.get(&b).unwrap_or(&0);

    let mut kw_positions: Vec<usize> = Vec::new();
    for k in KEYWORDS {
        let kc: Vec<char> = k.chars().collect();
        let mut from = 0;
        while from + kc.len() <= lower.len() {
            if lower[from..from + kc.len()] == kc[..] {
                kw_positions.push(from);
                from += kc.len().max(1);
            } else {
                from += 1;
            }
        }
    }
    let is_magic = magic_re().is_match(&hay_str).unwrap_or(false);
    if kw_positions.is_empty() && !is_magic {
        return None;
    }
    let subject_len = subject.chars().count();
    let mut best: Option<(String, f32)> = None;

    for m in candidate_re().find_iter(&hay_str).flatten() {
        let (start, end) = (ci(m.start()), ci(m.end()));
        let raw: String = hay[start..end].iter().collect();
        let token: String = raw.strip_prefix("G-").unwrap_or(&raw).chars().filter(|c| *c != ' ' && *c != '-').collect();
        let b_start = start.saturating_sub(12);
        let before: String = hay[b_start..start].iter().collect();
        if !plausible(&token, &before) {
            continue;
        }
        let distance = kw_positions.iter().map(|&p| (p as i64 - start as i64).unsigned_abs() as usize).min().unwrap_or(9999);
        if distance > 160 {
            continue;
        }
        let mut score = 1.0 - distance as f32 / 200.0;
        if start < subject_len {
            score += 0.35;
        }
        if token.chars().all(|c| c.is_ascii_digit()) {
            score += 0.15;
        }
        if token.chars().count() == 6 {
            score += 0.1;
        }
        // A line that is nothing but the code.
        let ls = hay[..start].iter().rposition(|&c| c == '\n').map(|p| p + 1).unwrap_or(0);
        let le = hay[end.saturating_sub(1)..].iter().position(|&c| c == '\n').map(|p| p + end.saturating_sub(1)).unwrap_or(hay.len());
        let line: String = hay[ls..le.max(ls)].iter().collect();
        if line.trim() == raw.trim() {
            score += 0.3;
        }
        if best.as_ref().map(|b| score > b.1).unwrap_or(true) {
            best = Some((raw.trim().to_string(), score));
        }
    }

    let mut valid_minutes = None;
    if let Ok(Some(v)) = validity_re().captures(&hay_str) {
        if let Ok(n) = v[1].parse::<u32>() {
            valid_minutes = Some(if v[2].to_lowercase().starts_with('h') { n * 60 } else { n });
        }
    }
    if best.is_none() && !is_magic {
        return None;
    }
    let service = from_name
        .map(|n| n.split(" <").next().unwrap_or("").trim().to_string())
        .filter(|s| !s.is_empty());
    let confidence = best.as_ref().map(|b| b.1).unwrap_or(0.6).clamp(0.0, 1.0);
    Some(OtpFinding { is_magic_link: best.is_none() && is_magic, code: best.map(|b| b.0), service, valid_minutes, confidence })
}
