import Foundation

/// Modified UTF-7 mailbox names (RFC 3501 section 5.1.3): `&` shifts into base64 UTF-16 using `,` for `/`.
public enum ModifiedUtf7 {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,".utf8)

    public static func encode(_ s: String) -> String {
        var sb = ""
        let units = Array(s.utf16)
        var i = 0
        while i < units.count {
            let u = units[i]
            if (0x20...0x7e).contains(u) {
                sb += u == 0x26 ? "&-" : String(UnicodeScalar(UInt8(u)))
                i += 1; continue
            }
            let start = i
            while i < units.count && !(0x20...0x7e).contains(units[i]) { i += 1 }
            var bytes = [UInt8]()
            for w in units[start..<i] { bytes.append(UInt8(w >> 8)); bytes.append(UInt8(w & 0xFF)) }
            sb += "&"
            var bits = 0, acc = 0
            for b in bytes {
                acc = (acc << 8) | Int(b)
                bits += 8
                while bits >= 6 {
                    bits -= 6
                    sb.append(Character(UnicodeScalar(alphabet[(acc >> bits) & 0x3F])))
                }
                acc &= (1 << bits) - 1
            }
            if bits > 0 { sb.append(Character(UnicodeScalar(alphabet[(acc << (6 - bits)) & 0x3F]))) }
            sb += "-"
        }
        return sb
    }

    public static func decode(_ s: String) -> String {
        guard s.contains("&") else { return s }
        let chars = Array(s)
        var sb = ""
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if c != "&" { sb.append(c); i += 1; continue }
            let end = chars[(i + 1)...].firstIndex(of: "-") ?? chars.count
            if end == i + 1 { sb.append("&"); i = end + 1; continue }
            var bytes = [UInt8]()
            var bits = 0, acc = 0
            for ch in chars[(i + 1)..<end] {
                guard let a = ch.asciiValue, let v = alphabet.firstIndex(of: a) else { continue }
                acc = (acc << 6) | v
                bits += 6
                if bits >= 8 {
                    bits -= 8
                    bytes.append(UInt8((acc >> bits) & 0xFF))
                    acc &= (1 << bits) - 1
                }
            }
            var units = [UInt16]()
            var k = 0
            while k + 1 < bytes.count { units.append(UInt16(bytes[k]) << 8 | UInt16(bytes[k + 1])); k += 2 }
            sb += String(decoding: units, as: UTF16.self)
            i = end + 1
        }
        return sb
    }
}

/// Compact UID sets: `[1,2,3,7,9,10]` becomes `"1:3,7,9:10"`.
public enum SequenceSet {
    public static func of(_ uids: [Int64]) -> String {
        let sorted = Array(Set(uids)).sorted()
        guard var start = sorted.first else { return "" }
        var prev = start
        var parts = [String]()
        func emit() { parts.append(start == prev ? "\(start)" : "\(start):\(prev)") }
        for u in sorted.dropFirst() {
            if u == prev + 1 { prev = u; continue }
            emit(); start = u; prev = u
        }
        emit()
        return parts.joined(separator: ",")
    }

    /// Chunks of at most `maxPerChunk` UIDs whose rendered sets stay short enough for any server.
    public static func chunked(_ uids: [Int64], maxPerChunk: Int = 500) -> [String] {
        let sorted = Array(Set(uids)).sorted()
        return stride(from: 0, to: sorted.count, by: maxPerChunk).map { of(Array(sorted[$0..<min($0 + maxPerChunk, sorted.count)])) }
    }

    /// Parse `1:3,7,9:*` against `maxUid` for `*`.
    public static func parse(_ set: String, maxUid: Int64 = Int64.max) -> [Int64] {
        set.split(separator: ",").flatMap { part -> [Int64] in
            let pieces = part.split(separator: ":", maxSplits: 1).map(String.init)
            guard let a = pieces.first else { return [] }
            let b = pieces.count > 1 ? pieces[1] : a
            guard let lo = a == "*" ? maxUid : Int64(a), let hi = b == "*" ? maxUid : Int64(b) else { return [] }
            let (x, y) = lo <= hi ? (lo, hi) : (hi, lo)
            if y - x > 1_000_000 { return [] }
            return Array(x...y)
        }
    }
}

/// Quoting for IMAP astrings.
public enum ImapQuote {
    public static func needsLiteral(_ s: String, utf8: Bool) -> Bool {
        s.contains("\r") || s.contains("\n") || (!utf8 && s.unicodeScalars.contains { $0.value > 0x7e })
    }
    public static func quote(_ s: String) -> String {
        "\"" + s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") + "\""
    }
}
