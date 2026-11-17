import Foundation

/// RFC 3501 ENVELOPE, decoded. Port of `Envelope` (mail-core/imap/FetchModel.kt).
public struct Envelope: Equatable, Sendable {
    public var date: Date?
    public var subject: String
    public var from: [EmailAddress]
    public var sender: [EmailAddress]
    public var replyTo: [EmailAddress]
    public var to: [EmailAddress]
    public var cc: [EmailAddress]
    public var bcc: [EmailAddress]
    public var inReplyTo: String?
    public var messageId: String?

    public static func parse(_ v: ImapValue) -> Envelope? {
        let f = v.items
        guard f.count >= 10 else { return nil }
        let subject = f[1].text.map {
            EncodedWords.decode($0)
                .components(separatedBy: CharacterSet(charactersIn: "\r\n\t"))
                .filter { !$0.isEmpty }.joined(separator: " ")
                .trimmingCharacters(in: .whitespaces)
        } ?? ""
        return Envelope(
            date: f[0].text.flatMap(MailDates.parse),
            subject: subject,
            from: addresses(f[2]), sender: addresses(f[3]), replyTo: addresses(f[4]),
            to: addresses(f[5]), cc: addresses(f[6]), bcc: addresses(f[7]),
            inReplyTo: f[8].text.flatMap { MessageIds.parse($0).first },
            messageId: f[9].text.flatMap { MessageIds.parse($0).first }
        )
    }

    /// `((name adl mailbox host) ...)`; host NIL marks an RFC 2822 group start/end, which we skip.
    private static func addresses(_ v: ImapValue) -> [EmailAddress] {
        v.items.compactMap { a in
            let p = a.items
            guard p.count >= 4, let mailbox = p[2].text, let host = p[3].text else { return nil }
            let name = p[0].text.map { EncodedWords.decode($0).trimmingCharacters(in: .whitespaces) }.flatMap { $0.isEmpty ? nil : $0 }
            return EmailAddress(address: "\(mailbox)@\(host)", name: name)
        }
    }
}

public enum MessageIds {
    /// Every `<id>` in a header value, brackets removed.
    public static func parse(_ raw: String) -> [String] {
        var out = [String]()
        var rest = Substring(raw)
        while let open = rest.firstIndex(of: "<"), let close = rest[open...].firstIndex(of: ">") {
            let id = rest[rest.index(after: open)..<close].trimmingCharacters(in: .whitespaces)
            if !id.isEmpty { out.append(id) }
            rest = rest[rest.index(after: close)...]
        }
        return out
    }
}

public enum MailDates {
    private static let formats = [
        "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z", "d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm Z",
        "EEE, d MMM yy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz",
    ]

    /// RFC 5322 dates, tolerant of a trailing `(UTC)` comment and a missing weekday.
    public static func parse(_ raw: String) -> Date? {
        var s = raw.trimmingCharacters(in: .whitespaces)
        if let paren = s.firstIndex(of: "(") { s = String(s[..<paren]).trimmingCharacters(in: .whitespaces) }
        s = s.replacingOccurrences(of: "  ", with: " ")
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        for fmt in formats {
            f.dateFormat = fmt
            if let d = f.date(from: s) { return d }
        }
        return nil
    }
}
