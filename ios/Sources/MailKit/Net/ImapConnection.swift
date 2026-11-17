// Apple-only: Network.framework carries the bytes; MailKit's own parser reads them.
// EVIDENCE: CODE-ONLY here (no Apple SDK in the build container). First compile happens on the
// macOS CI lane (.github/workflows/ios.yml); first real connection is owner-verified on a device.
#if canImport(Network)
import Foundation
import Network

public enum ImapConnectionError: Error, Equatable {
    case closed
    case timedOut
    case authenticationFailed(String)
    case serverRefused(String)
    case insecureTransportRefused
}

/// One TLS connection to an IMAP server speaking the bits the first iOS release needs:
/// implicit TLS (port 993) sign-in with LOGIN, SELECT, and a header-only envelope fetch.
///
/// Rules carried over from the Android core: TLS is never silently downgraded (a server that does
/// not offer implicit TLS is an error, not a cue to fall back to plaintext); credentials never reach
/// a log. STARTTLS, IDLE, CONDSTORE/QRESYNC, COMPRESS and OAuth are not ported yet - the plan lists them.
public final class ImapConnection: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "dev.aarso.mail.imap")
    private var buffer = [UInt8]()
    private var tagCounter = 0

    public init(host: String, port: Int, security: Security) throws {
        guard security == .sslTls else { throw ImapConnectionError.insecureTransportRefused }
        let params = NWParameters(tls: NWProtocolTLS.Options(), tcp: NWProtocolTCP.Options())
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: UInt16(port))!, using: params)
    }

    public func open() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            var resumed = false
            connection.stateUpdateHandler = { state in
                guard !resumed else { return }
                switch state {
                case .ready: resumed = true; cont.resume()
                case .failed(let e): resumed = true; cont.resume(throwing: e)
                case .cancelled: resumed = true; cont.resume(throwing: ImapConnectionError.closed)
                default: break
                }
            }
            connection.start(queue: queue)
        }
        // Greeting: `* OK ...` (or PREAUTH). Anything else is a refusal.
        let greeting = try await nextResponse()
        guard greeting.isOk else { throw ImapConnectionError.serverRefused(String(describing: greeting)) }
    }

    public func close() { connection.cancel() }

    // MARK: commands

    /// Sends one command and collects every untagged response until its tagged completion.
    @discardableResult
    public func command(_ text: String, redactedAs log: String? = nil) async throws -> (untagged: [ImapResponse], status: ImapResponse) {
        tagCounter += 1
        let tag = "A\(tagCounter)"
        try await send(Array("\(tag) \(text)\r\n".utf8))
        var untagged = [ImapResponse]()
        while true {
            let r = try await nextResponse()
            if case .status(let t, _, _, _) = r, t == tag { return (untagged, r) }
            untagged.append(r)
        }
    }

    public func login(user: String, password: String) async throws {
        let (_, status) = try await command("LOGIN \(ImapQuote.quote(user)) \(ImapQuote.quote(password))", redactedAs: "LOGIN <redacted>")
        guard case .status(_, let kind, _, let text) = status else { throw ImapConnectionError.authenticationFailed("") }
        guard kind == "OK" else { throw ImapConnectionError.authenticationFailed(text) }
    }

    /// Selects a mailbox and returns the EXISTS count.
    public func select(_ mailbox: String) async throws -> Int64 {
        let (untagged, status) = try await command("SELECT \(ImapQuote.quote(ModifiedUtf7.encode(mailbox)))")
        guard status.isOk else { throw ImapConnectionError.serverRefused(String(describing: status)) }
        for case .data(let n?, "EXISTS", _) in untagged { return n }
        return 0
    }

    public struct Header: Equatable, Sendable {
        public let uid: Int64
        public let seen: Bool
        public let envelope: Envelope
    }

    /// The newest `limit` messages' envelopes (read-only: BODY.PEEK is never needed for ENVELOPE).
    public func recentHeaders(exists: Int64, limit: Int = 50) async throws -> [Header] {
        guard exists > 0 else { return [] }
        let first = max(1, exists - Int64(limit) + 1)
        let (untagged, status) = try await command("FETCH \(first):\(exists) (UID FLAGS ENVELOPE)")
        guard status.isOk else { throw ImapConnectionError.serverRefused(String(describing: status)) }
        var out = [Header]()
        for case .data(_, "FETCH", let values) in untagged {
            let items = values.first?.items ?? []
            var uid: Int64?, seen = false, env: Envelope?
            var i = 0
            while i + 1 < items.count {
                switch items[i].text?.uppercased() {
                case "UID": uid = items[i + 1].long
                case "FLAGS": seen = items[i + 1].items.contains { $0.text?.lowercased() == "\\seen" }
                case "ENVELOPE": env = Envelope.parse(items[i + 1])
                default: break
                }
                i += 2
            }
            if let uid = uid, let env = env { out.append(Header(uid: uid, seen: seen, envelope: env)) }
        }
        return out.sorted { $0.uid > $1.uid }
    }

    public func logout() async {
        _ = try? await command("LOGOUT")
        close()
    }

    // MARK: bytes

    private func send(_ bytes: [UInt8]) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: Data(bytes), completion: .contentProcessed { error in
                if let error = error { cont.resume(throwing: error) } else { cont.resume() }
            })
        }
    }

    private func receiveMore() async throws {
        let data: Data = try await withCheckedThrowingContinuation { cont in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if let error = error { cont.resume(throwing: error) }
                else if let data = data, !data.isEmpty { cont.resume(returning: data) }
                else { cont.resume(throwing: isComplete ? ImapConnectionError.closed : ImapConnectionError.timedOut) }
            }
        }
        buffer += data
    }

    private func nextResponse() async throws -> ImapResponse {
        while true {
            if let (response, used) = try ImapResponseParser.parse(buffer) {
                buffer.removeFirst(used)
                return response
            }
            try await receiveMore()
        }
    }
}
#endif
