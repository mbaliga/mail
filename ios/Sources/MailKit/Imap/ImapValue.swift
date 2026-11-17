import Foundation

/// One IMAP data item (RFC 3501 section 4): an atom, a string (quoted or literal), NIL, or a list.
public indirect enum ImapValue: Equatable, Sendable {
    case atom(String)
    case str([UInt8])
    case null
    case list([ImapValue])

    /// Atoms and strings both read as text; NIL and lists read as nil.
    public var text: String? {
        switch self {
        case .atom(let t): return t
        case .str(let b): return String(decoding: b, as: UTF8.self)
        case .null, .list: return nil
        }
    }
    public var long: Int64? { text.flatMap { Int64($0) } }
    public var items: [ImapValue] {
        if case .list(let l) = self { return l }
        return []
    }
    public var bytes: [UInt8]? {
        switch self {
        case .str(let b): return b
        case .atom(let t): return Array(t.utf8)
        default: return nil
        }
    }
}

/// `[UIDVALIDITY 3857529045]`, `[PERMANENTFLAGS (\Seen \*)]`, `[APPENDUID 38505 3955]` ...
public struct ResponseCode: Equatable, Sendable {
    public let name: String
    public let values: [ImapValue]
    public func arg(_ i: Int) -> String? { values.indices.contains(i) ? values[i].text : nil }
}

public enum ImapResponse: Equatable, Sendable {
    /// `+ text`: the server wants the rest of a literal, a SASL step, or is idling.
    case continuation(String)
    /// `tag OK [CODE] text` or `* OK|NO|BAD|BYE|PREAUTH [CODE] text`; `tag` is nil when untagged.
    case status(tag: String?, kind: String, code: ResponseCode?, text: String)
    /// `* 23 EXISTS`, `* 4 FETCH (...)`, `* CAPABILITY ...`, `* LIST (...) "/" name`, `* SEARCH 1 2 3` ...
    case data(number: Int64?, keyword: String, values: [ImapValue])

    public var isOk: Bool {
        if case .status(_, let kind, _, _) = self { return kind == "OK" || kind == "PREAUTH" }
        return false
    }
}

public struct ImapParseError: Error, Equatable { public let message: String }

/// Parses IMAP responses out of a growing byte buffer.
///
/// Port of `ImapReader` (mail-core/imap/ImapProtocol.kt), restructured for an async socket: instead
/// of blocking on an `InputStream`, `parse` returns `nil` when the buffer does not yet hold a whole
/// response, and the caller appends more bytes and tries again. The grammar's one hard property is
/// kept: a literal (`{1234}`) is a byte count followed by that many raw octets in the middle of a
/// response, so one response may span many lines and contain CRLF inside a literal.
public enum ImapResponseParser {

    /// Returns the next complete response and how many bytes of `buffer` it used, or nil if more bytes are needed.
    public static func parse(_ buffer: [UInt8]) throws -> (response: ImapResponse, consumed: Int)? {
        let c = Cursor(buffer)
        do {
            let r = try parseResponse(c)
            return (r, c.i)
        } catch is Incomplete {
            return nil
        }
    }

    // MARK: cursor

    struct Incomplete: Error {}

    final class Cursor {
        let b: [UInt8]
        var i = 0
        init(_ b: [UInt8]) { self.b = b }
        func peek() throws -> UInt8 {
            guard i < b.count else { throw Incomplete() }
            return b[i]
        }
        @discardableResult func read() throws -> UInt8 {
            let c = try peek(); i += 1; return c
        }
    }

    private static let sp = UInt8(ascii: " "), cr = UInt8(ascii: "\r"), lf = UInt8(ascii: "\n")
    private static let lparen = UInt8(ascii: "("), rparen = UInt8(ascii: ")")
    private static let lbrack = UInt8(ascii: "["), rbrack = UInt8(ascii: "]")

    private static func parseResponse(_ c: Cursor) throws -> ImapResponse {
        if try c.peek() == UInt8(ascii: "+") {
            try c.read()
            if try c.peek() == sp { try c.read() }
            return .continuation(try readRestOfLine(c))
        }
        let tag = try readToken(c)
        try skipSpaces(c)
        if tag == "*" {
            let word = try readToken(c)
            if let number = Int64(word) {
                try skipSpaces(c)
                let keyword = try readToken(c).uppercased()
                return .data(number: number, keyword: keyword, values: try readValuesToEndOfLine(c))
            }
            let upper = word.uppercased()
            switch upper {
            case "OK", "NO", "BAD", "BYE", "PREAUTH": return try readStatus(c, tag: nil, kind: upper)
            default: return .data(number: nil, keyword: upper, values: try readValuesToEndOfLine(c))
            }
        }
        let kind = try readToken(c).uppercased()
        return try readStatus(c, tag: tag, kind: kind)
    }

    private static func readStatus(_ c: Cursor, tag: String?, kind: String) throws -> ImapResponse {
        try skipSpaces(c)
        var code: ResponseCode?
        if !(try atEol(c)), try c.peek() == lbrack {
            try c.read()
            var content = [UInt8]()
            var depth = 1
            while true {
                let ch = try c.read()
                if ch == lbrack { depth += 1 }
                if ch == rbrack { depth -= 1; if depth == 0 { break } }
                if ch == lf { break }
                content.append(ch)
            }
            code = try parseCode(String(decoding: content, as: UTF8.self))
            try skipSpaces(c)
        }
        return .status(tag: tag, kind: kind, code: code, text: try readRestOfLine(c))
    }

    private static func parseCode(_ content: String) throws -> ResponseCode {
        let name: String
        let rest: String
        if let sp = content.firstIndex(of: " ") {
            name = String(content[..<sp]).uppercased()
            rest = String(content[content.index(after: sp)...])
        } else {
            name = content.uppercased(); rest = ""
        }
        var values = [ImapValue]()
        if !rest.isEmpty {
            let sub = Cursor(Array((rest + "\r\n").utf8))
            values = try readValuesToEndOfLine(sub)
        }
        return ResponseCode(name: name, values: values)
    }

    /// Values up to and including the end of the line, reading through any literals.
    static func readValuesToEndOfLine(_ c: Cursor) throws -> [ImapValue] {
        var out = [ImapValue]()
        while true {
            try skipSpaces(c)
            if try atEol(c) { try consumeEol(c); return out }
            out.append(try readValue(c))
        }
    }

    private static func readValue(_ c: Cursor) throws -> ImapValue {
        switch try c.peek() {
        case lparen:
            try c.read()
            var items = [ImapValue]()
            while true {
                try skipSpaces(c)
                let ch = try c.peek()
                if ch == rparen { try c.read(); break }
                if ch == cr || ch == lf { break } // unterminated list at end of line: tolerate
                items.append(try readValue(c))
            }
            return .list(items)
        case UInt8(ascii: "\""):
            return .str(try readQuoted(c))
        case UInt8(ascii: "{"):
            return .str(try readLiteral(c))
        case UInt8(ascii: "~"):
            try c.read()
            if try c.peek() == UInt8(ascii: "{") { return .str(try readLiteral(c)) }
            return .atom("~" + (try readAtomTail(c)))
        default:
            let atom = try readAtomTail(c)
            return atom.caseInsensitiveCompare("NIL") == .orderedSame ? .null : .atom(atom)
        }
    }

    private static func readQuoted(_ c: Cursor) throws -> [UInt8] {
        try c.read() // opening quote
        var out = [UInt8]()
        while true {
            let ch = try c.read()
            switch ch {
            case UInt8(ascii: "\""): return out
            case UInt8(ascii: "\\"): out.append(try c.read())
            case cr, lf: return out // broken server; never hang
            default: out.append(ch)
            }
        }
    }

    private static func readLiteral(_ c: Cursor) throws -> [UInt8] {
        try c.read() // {
        var digits = ""
        while true {
            let ch = try c.read()
            if ch == UInt8(ascii: "}") { break }
            if ch == UInt8(ascii: "+") || ch == UInt8(ascii: "-") { continue }
            digits.append(Character(UnicodeScalar(ch)))
        }
        guard let n = Int(digits), n >= 0 else { throw ImapParseError(message: "Bad literal length: \(digits)") }
        if try c.peek() == cr { try c.read() }
        if try c.peek() == lf { try c.read() }
        guard c.i + n <= c.b.count else { throw Incomplete() }
        let out = Array(c.b[c.i..<(c.i + n)])
        c.i += n
        return out
    }

    /// An atom, or a `BODY[...]`-shaped key whose brackets may hold spaces and parentheses.
    private static func readAtomTail(_ c: Cursor) throws -> String {
        var sb = [UInt8]()
        var depth = 0
        while true {
            let ch = try c.peek()
            if depth == 0 && (ch == sp || ch == lparen || ch == rparen || ch == cr || ch == lf) { break }
            if depth > 0 && (ch == cr || ch == lf) { break }
            if ch == lbrack { depth += 1 }
            if ch == rbrack && depth > 0 { depth -= 1 }
            sb.append(try c.read())
        }
        return String(decoding: sb, as: UTF8.self)
    }

    private static func readToken(_ c: Cursor) throws -> String {
        var sb = [UInt8]()
        while true {
            let ch = try c.peek()
            if ch == sp || ch == cr || ch == lf { break }
            if ch == lbrack && !sb.isEmpty { break }
            sb.append(try c.read())
        }
        return String(decoding: sb, as: UTF8.self)
    }

    private static func readRestOfLine(_ c: Cursor) throws -> String {
        var out = [UInt8]()
        while true {
            let ch = try c.read()
            if ch == lf { break }
            if ch != cr { out.append(ch) }
        }
        return String(decoding: out, as: UTF8.self)
    }

    private static func skipSpaces(_ c: Cursor) throws { while try c.peek() == sp { try c.read() } }
    private static func atEol(_ c: Cursor) throws -> Bool { let ch = try c.peek(); return ch == cr || ch == lf }
    private static func consumeEol(_ c: Cursor) throws {
        if try c.peek() == cr { try c.read() }
        if try c.peek() == lf { try c.read() }
    }
}
