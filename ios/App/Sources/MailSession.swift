import Foundation
import MailKit

/// A signed-in account. Holds a Keychain *key*, never the password.
struct Account: Codable, Equatable {
    var email: String
    var username: String
    var host: String
    var port: Int
    var providerName: String
    var secretKey: String
}

struct InboxRow: Identifiable, Equatable {
    let id: Int64
    let from: String
    let subject: String
    let seen: Bool
    let date: Date?
    /// A code found by the on-device heuristics (no model, no network involved).
    let otp: String?
}

@MainActor
final class MailSession: ObservableObject {
    @Published private(set) var account: Account?
    @Published private(set) var rows: [InboxRow] = []
    @Published private(set) var loading = false
    @Published var errorText: String?

    private static let accountKey = "account.v1"

    init() {
        if let data = UserDefaults.standard.data(forKey: Self.accountKey),
           let a = try? JSONDecoder().decode(Account.self, from: data) {
            account = a
        }
    }

    func signIn(email: String, password: String) async {
        errorText = nil
        let trimmed = email.trimmingCharacters(in: .whitespaces)
        guard let preset = Providers.forEmail(trimmed) else {
            errorText = "Server settings for this address are not known yet. Custom-server setup is not on iOS yet."
            return
        }
        guard preset.signInSupportedOnIOS, let imap = preset.imap else {
            errorText = preset.unsupportedReason ?? "\(preset.name) needs sign-in with the provider, which is not on iOS yet. Use an app password if the provider offers one."
            return
        }
        let user = preset.username(for: trimmed)
        let key = "acct.\(trimmed.lowercased())"
        do {
            // Prove the credentials before storing anything.
            let c = try ImapConnection(host: imap.host, port: imap.port, security: imap.security)
            try await c.open()
            try await c.login(user: user, password: password)
            await c.logout()
            try KeychainStore.set(password, for: key)
            let a = Account(email: trimmed, username: user, host: imap.host, port: imap.port, providerName: preset.name, secretKey: key)
            UserDefaults.standard.set(try JSONEncoder().encode(a), forKey: Self.accountKey)
            account = a
            await refresh()
        } catch ImapConnectionError.authenticationFailed(let text) {
            errorText = "\(preset.name) refused the sign-in: \(text)"
        } catch {
            errorText = "Could not sign in: \(error.localizedDescription)"
        }
    }

    func signOut() {
        if let a = account { KeychainStore.delete(a.secretKey) }
        UserDefaults.standard.removeObject(forKey: Self.accountKey)
        account = nil
        rows = []
    }

    func refresh() async {
        guard let a = account, let password = KeychainStore.get(a.secretKey) else { return }
        loading = true
        defer { loading = false }
        do {
            let c = try ImapConnection(host: a.host, port: a.port, security: .sslTls)
            try await c.open()
            try await c.login(user: a.username, password: password)
            let exists = try await c.select("INBOX")
            let headers = try await c.recentHeaders(exists: exists, limit: 50)
            await c.logout()
            rows = headers.map { h in
                let from = h.envelope.from.first?.displayName() ?? "Unknown sender"
                let found = OtpExtractor.find(subject: h.envelope.subject, text: "", fromName: from)
                return InboxRow(id: h.uid, from: from, subject: h.envelope.subject, seen: h.seen, date: h.envelope.date, otp: found?.code)
            }
            errorText = nil
        } catch {
            errorText = "Could not refresh: \(error.localizedDescription)"
        }
    }

    var unseenCount: Int { rows.filter { !$0.seen }.count }
}
