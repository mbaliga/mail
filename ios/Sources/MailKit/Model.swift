import Foundation

/// An RFC 5322 mailbox. Mirrors `dev.aarso.mail.model.EmailAddress`.
public struct EmailAddress: Equatable, Hashable, Sendable, CustomStringConvertible {
    public let address: String
    public let name: String?

    public init(address: String, name: String? = nil) {
        self.address = address
        self.name = name
    }

    public var normalized: String { address.trimmingCharacters(in: .whitespaces).lowercased() }
    public var domain: String {
        guard let at = normalized.lastIndex(of: "@") else { return "" }
        return String(normalized[normalized.index(after: at)...])
    }
    public var localPart: String {
        guard let at = normalized.lastIndex(of: "@") else { return normalized }
        return String(normalized[..<at])
    }

    /// What a person reads: the display name when there is a meaningful one, else the address.
    public func displayName() -> String {
        if let n = name, !n.trimmingCharacters(in: .whitespaces).isEmpty, n.caseInsensitiveCompare(address) != .orderedSame {
            return n
        }
        return address
    }

    public var description: String {
        if let n = name, !n.isEmpty { return "\(n) <\(address)>" }
        return address
    }
}

public enum IncomingProtocol: String, Sendable { case imap, jmap, pop3 }
public enum Security: String, Sendable { case sslTls, startTls, none }
public enum AuthKind: String, Sendable { case password, appPassword, oauth2, apiToken }

public struct ServerTemplate: Equatable, Sendable {
    public let host: String
    public let port: Int
    public let security: Security
    public init(_ host: String, _ port: Int, _ security: Security) {
        self.host = host; self.port = port; self.security = security
    }
}
