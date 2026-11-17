import Foundation

/// A one-time code or sign-in link found in a message. Mirrors `OtpFinding`.
public struct OtpFinding: Equatable, Sendable {
    public let code: String?
    public let isMagicLink: Bool
    public let service: String?
    /// Minutes the sender says it is valid for; nil when unstated (the UI assumes 15).
    public let validMinutes: Int?
    public let confidence: Float
}

/// Finds verification codes the way a person scans for them: a 4-8 digit (or short alphanumeric)
/// token *near* a word that means "code" - and not a year, price, phone number or order number.
/// Port of `OtpExtractor` (mail-core/intel/Otp.kt); `\w` is spelled ASCII-only to match Java's default.
public enum OtpExtractor {
    private static let keywords = [
        "verification code", "verify", "one-time", "one time", "otp", "passcode", "pass code", "security code", "login code",
        "log-in code", "sign-in code", "sign in code", "authentication code", "confirmation code", "access code", "2fa", "two-factor",
        "two factor", "2-step", "your code", "the code", "code is", "code:", "pin", "auth code", "temporary code",
        "bestätigungscode", "sicherheitscode", "verifizierungscode", "code de vérification", "code de sécurité", "code de confirmation",
        "código de verificación", "código de seguridad", "código", "codice di verifica", "codice", "verificatiecode",
        "kod weryfikacyjny", "код подтверждения", "код", "验证码", "認証コード", "確認コード", "인증번호", "인증 코드", "doğrulama kodu",
    ]

    private static let magic = try! NSRegularExpression(
        pattern: "(magic link|sign[- ]?in link|log[- ]?in link|click (the )?(link|button) (below )?to (sign|log) ?in|confirm your (sign[- ]?in|login)|one-click sign[- ]?in)",
        options: [.caseInsensitive])

    private static let candidate = try! NSRegularExpression(
        pattern: "(?<![A-Za-z0-9_$€£¥₹/.:#-])(G-)?(\\d{3,4}[ -]\\d{3,4}|\\d{4,8}|[A-Z0-9]{6,8})(?![A-Za-z0-9_%/:-]|\\.\\d)")

    private static let validity = try! NSRegularExpression(
        pattern: "(?:valid|expires?|expiring|good)\\s+(?:for|in|within)\\s+(\\d{1,3})\\s*(min|minute|minutes|mins|hour|hours|h)\\b",
        options: [.caseInsensitive])

    public static func find(subject: String, text: String, fromName: String?) -> OtpFinding? {
        let body = (text as NSString).substring(to: min(4000, (text as NSString).length))
        let hay = "\(subject)\n" + body
        let ns = hay as NSString
        let lower = hay.lowercased() as NSString
        let full = NSRange(location: 0, length: ns.length)

        var keywordPositions = [Int]()
        for k in keywords {
            var from = 0
            while from < lower.length {
                let r = lower.range(of: k, options: [], range: NSRange(location: from, length: lower.length - from))
                if r.location == NSNotFound { break }
                keywordPositions.append(r.location)
                from = r.location + max(r.length, 1)
            }
        }
        keywordPositions.sort()
        let isMagic = magic.firstMatch(in: hay, range: full) != nil
        if keywordPositions.isEmpty && !isMagic { return nil }

        let subjectLength = (subject as NSString).length
        var best: (String, Float)?
        for m in candidate.matches(in: hay, range: full) {
            let raw = ns.substring(with: m.range)
            var token = raw
            if token.hasPrefix("G-") { token.removeFirst(2) }
            token = token.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "")
            if !plausible(token, ns, m.range.location) { continue }
            let distance = keywordPositions.map { abs($0 - m.range.location) }.min() ?? 9999
            if distance > 160 { continue }
            var score = 1 - Float(distance) / 200
            if m.range.location < subjectLength { score += 0.35 }
            if token.allSatisfy({ $0.isASCII && $0.isNumber }) { score += 0.15 }
            if token.count == 6 { score += 0.1 }
            // A line that is nothing but the code.
            let lineStart = ns.range(of: "\n", options: .backwards, range: NSRange(location: 0, length: m.range.location)).location
            let ls = lineStart == NSNotFound ? 0 : lineStart + 1
            let endSearchFrom = m.range.location + m.range.length - 1
            let nl = ns.range(of: "\n", options: [], range: NSRange(location: endSearchFrom, length: ns.length - endSearchFrom))
            let le = nl.location == NSNotFound ? ns.length : nl.location
            if ns.substring(with: NSRange(location: ls, length: max(0, le - ls))).trimmingCharacters(in: .whitespaces)
                == raw.trimmingCharacters(in: .whitespaces) { score += 0.3 }
            if best == nil || score > best!.1 { best = (raw.trimmingCharacters(in: .whitespaces), score) }
        }

        var validMinutes: Int?
        if let v = validity.firstMatch(in: hay, range: full), let n = Int(ns.substring(with: v.range(at: 1))) {
            validMinutes = ns.substring(with: v.range(at: 2)).lowercased().hasPrefix("h") ? n * 60 : n
        }
        if best == nil && !isMagic { return nil }
        let service = fromName.flatMap { $0.components(separatedBy: " <")[0].trimmingCharacters(in: .whitespaces) }.flatMap { $0.isEmpty ? nil : $0 }
        return OtpFinding(
            code: best?.0, isMagicLink: best == nil && isMagic, service: service, validMinutes: validMinutes,
            confidence: min(max(best?.1 ?? 0.6, 0), 1))
    }

    private static func plausible(_ token: String, _ hay: NSString, _ at: Int) -> Bool {
        if token.count < 4 || token.count > 8 { return false }
        let digits = token.filter { $0.isNumber }.count
        if token.contains(where: { $0.isLetter }) {
            // Alphanumeric codes must mix letters and digits and be upper-case; words are not codes.
            if digits == 0 || token.contains(where: { $0.isLowercase }) { return false }
        }
        if token.allSatisfy({ $0.isNumber }) {
            guard let n = Int64(token) else { return false }
            if token.count == 4 && (1900...2099).contains(n) { return false } // a year
            if Set(token).count == 1 && token.count < 6 { return false } // 0000, 1111
        }
        let start = max(0, at - 12)
        let before = hay.substring(with: NSRange(location: start, length: at - start)).lowercased()
        for bad in ["order", "invoice", "#", "tel", "phone", "ref", "account ending", "card ending"] where before.contains(bad) {
            return false
        }
        return true
    }
}
