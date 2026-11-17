import Foundation

/// Charset resolution that survives real mail. Port of `MailCharsets` (mail-core/mime/Codecs.kt).
///
/// Decoding never throws and never drops the message: an unknown name resolves through the alias
/// table, then falls back to UTF-8; malformed bytes become U+FFFD.
public enum MailCharsets {
    private static let aliases: [String: String] = [
        "utf8": "utf-8", "utf-8": "utf-8", "unicode-1-1-utf-8": "utf-8", "x-unknown": "utf-8",
        "unknown-8bit": "windows-1252", "us-ascii": "windows-1252", "ascii": "windows-1252",
        "ansi_x3.4-1968": "windows-1252",
        // ISO-8859-1 mail is overwhelmingly windows-1252 in practice (smart quotes in 0x80-0x9F).
        "iso-8859-1": "windows-1252", "iso8859-1": "windows-1252", "latin1": "windows-1252", "latin-1": "windows-1252",
        "cp1252": "windows-1252", "cp1251": "windows-1251", "cp1250": "windows-1250",
        "shift-jis": "shift_jis", "sjis": "shift_jis", "x-sjis": "shift_jis", "cp932": "shift_jis",
        "euc-kr": "euc-kr", "ks_c_5601-1987": "euc-kr", "ks_c_5601": "euc-kr",
        "gb2312": "gbk", "gb_2312-80": "gbk", "x-gbk": "gbk", "cp936": "gbk",
    ]

    /// `nil` means "decode as UTF-8" (the universal fallback).
    static func encoding(for name: String?) -> String.Encoding? {
        guard let raw = name else { return nil }
        let cleaned = raw.trimmingCharacters(in: CharacterSet(charactersIn: " \t\"'")).lowercased()
        if cleaned.isEmpty { return nil }
        let mapped = aliases[cleaned] ?? cleaned
        switch mapped {
        case "utf-8": return .utf8
        case "windows-1252": return .windowsCP1252
        case "windows-1251": return .windowsCP1251
        case "windows-1250": return .windowsCP1250
        case "iso-8859-2": return .isoLatin2
        case "shift_jis": return .shiftJIS
        case "euc-jp": return .japaneseEUC
        case "iso-2022-jp": return .iso2022JP
        case "utf-16", "utf-16le", "utf-16be": return mapped == "utf-16be" ? .utf16BigEndian : (mapped == "utf-16le" ? .utf16LittleEndian : .utf16)
        default: return nil
        }
    }

    /// Decode without ever throwing; malformed or unmappable input becomes U+FFFD (UTF-8) or falls back to Latin-1.
    public static func decode(_ bytes: [UInt8], charset: String?) -> String {
        if bytes.isEmpty { return "" }
        if let enc = encoding(for: charset), enc != .utf8 {
            // windows-1252 is by far the most common legacy charset and swift-corelibs-foundation (Linux, and so
            // the CI lane) does not decode it reliably, so it is mapped by table on every platform.
            if enc == .windowsCP1252 { return decodeWindows1252(bytes) }
            if let s = String(bytes: bytes, encoding: enc) { return s }
            return String(bytes: bytes, encoding: .isoLatin1) ?? String(decoding: bytes, as: UTF8.self)
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    /// 0x80-0x9F of windows-1252 as code points; the five undefined bytes map to the C1 controls (WHATWG behaviour).
    private static let cp1252High: [UInt32] = [
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
    ]

    static func decodeWindows1252(_ bytes: [UInt8]) -> String {
        var out = String.UnicodeScalarView()
        for b in bytes {
            let cp: UInt32 = (b >= 0x80 && b <= 0x9F) ? cp1252High[Int(b) - 0x80] : UInt32(b)
            out.append(Unicode.Scalar(cp) ?? "\u{FFFD}")
        }
        return String(out)
    }

    /// Strict UTF-8 if it decodes cleanly, else windows-1252 (what undeclared Western mail almost always is).
    public static func sniff(_ bytes: [UInt8]) -> String {
        String(bytes: bytes, encoding: .utf8) != nil ? "utf-8" : "windows-1252"
    }
}

/// Content-Transfer-Encoding codecs (RFC 2045 section 6). Tolerant on decode, strict on encode.
public enum TransferEncoding {
    public static func decode(_ bytes: [UInt8], encoding: String?) -> [UInt8] {
        switch encoding?.trimmingCharacters(in: .whitespaces).lowercased() {
        case "base64": return decodeBase64(bytes)
        case "quoted-printable": return decodeQuotedPrintable(bytes)
        default: return bytes
        }
    }

    private static let b64: [Int] = {
        var t = [Int](repeating: -1, count: 256)
        for (i, c) in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".utf8.enumerated() { t[Int(c)] = i }
        return t
    }()

    /// A bit-accumulator decoder: line breaks and junk are skipped, missing padding is fine, and
    /// padding mid-stream (some encoders pad every line) resets the group.
    public static func decodeBase64(_ bytes: [UInt8]) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(bytes.count * 3 / 4 + 3)
        var acc = 0
        var bits = 0
        for b in bytes {
            let v = b64[Int(b)]
            if v >= 0 {
                acc = ((acc << 6) | v) & 0xFFFFFF
                bits += 6
                if bits >= 8 {
                    bits -= 8
                    out.append(UInt8((acc >> bits) & 0xFF))
                }
            } else if b == UInt8(ascii: "=") {
                acc = 0
                bits = 0
            }
        }
        return out
    }

    private static func hex(_ b: UInt8?) -> Int {
        guard let b = b else { return -1 }
        switch b {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): return Int(b - UInt8(ascii: "0"))
        case UInt8(ascii: "A")...UInt8(ascii: "F"): return Int(b - UInt8(ascii: "A")) + 10
        case UInt8(ascii: "a")...UInt8(ascii: "f"): return Int(b - UInt8(ascii: "a")) + 10
        default: return -1
        }
    }

    public static func decodeQuotedPrintable(_ bytes: [UInt8]) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(bytes.count)
        let n = bytes.count
        var i = 0
        while i < n {
            let b = bytes[i]
            if b != UInt8(ascii: "=") { out.append(b); i += 1; continue }
            // Soft line break, tolerating whitespace an MTA added before the break.
            var j = i + 1
            while j < n && (bytes[j] == UInt8(ascii: " ") || bytes[j] == UInt8(ascii: "\t")) { j += 1 }
            if j < n && bytes[j] == UInt8(ascii: "\r") {
                j += 1
                if j < n && bytes[j] == UInt8(ascii: "\n") { j += 1 }
                i = j; continue
            }
            if j < n && bytes[j] == UInt8(ascii: "\n") { i = j + 1; continue }
            if j == n { i = n; continue } // "=" at the very end: a soft break with no newline
            let h = hex(i + 1 < n ? bytes[i + 1] : nil)
            let l = hex(i + 2 < n ? bytes[i + 2] : nil)
            if h >= 0 && l >= 0 {
                out.append(UInt8(h * 16 + l)); i += 3
            } else {
                out.append(b); i += 1 // a literal '=' that encodes nothing: keep it, never drop text
            }
        }
        return out
    }

    /// RFC 2045 base64 body: 76-character lines, CRLF.
    public static func encodeBase64Lines(_ bytes: [UInt8]) -> String {
        let enc = Array(Data(bytes).base64EncodedString().utf8)
        var out = ""
        var i = 0
        while i < enc.count {
            out += String(decoding: enc[i..<min(i + 76, enc.count)], as: UTF8.self) + "\r\n"
            i += 76
        }
        return out
    }

    /// Quoted-printable for text bodies: UTF-8 bytes, `=XX` outside printable ASCII, soft breaks to
    /// keep lines <= 76, trailing whitespace protected, CRLF line endings.
    public static func encodeQuotedPrintable(_ text: String) -> String {
        let normalised = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let lines = normalised.components(separatedBy: "\n")
        var sb = ""
        for (li, line) in lines.enumerated() {
            var col = 0
            let bytes = Array(line.utf8)
            for (bi, b) in bytes.enumerated() {
                let isLast = bi == bytes.count - 1
                let token: String
                if b == UInt8(ascii: "=") {
                    token = "=3D"
                } else if (b == UInt8(ascii: " ") || b == UInt8(ascii: "\t")) && isLast {
                    token = String(format: "=%02X", Int(b))
                } else if (33...126).contains(b) || b == UInt8(ascii: " ") || b == UInt8(ascii: "\t") {
                    token = String(UnicodeScalar(b))
                } else {
                    token = String(format: "=%02X", Int(b))
                }
                if col + token.utf8.count > 75 { sb += "=\r\n"; col = 0 }
                sb += token; col += token.utf8.count
            }
            if li != lines.count - 1 { sb += "\r\n" }
        }
        return sb
    }
}

/// RFC 2047 encoded-words (`=?utf-8?B?...?=`, `=?iso-8859-1?Q?...?=`) in headers.
///
/// Whitespace *between* two adjacent encoded words is dropped (it is folding, not content); a word
/// split mid-character across two words of the same charset is joined before decoding; an RFC 2231
/// language tag (`=?utf-8*en?Q?...?=`) is accepted and ignored.
public enum EncodedWords {
    private static let word = try! NSRegularExpression(pattern: "=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=")

    public static func decode(_ raw: String) -> String {
        guard raw.contains("=?") else { return raw }
        let ns = raw as NSString
        var out = ""
        var last = 0
        var pendingCharset: String?
        var pending = [UInt8]()

        func flush() {
            if let cs = pendingCharset {
                out += MailCharsets.decode(pending, charset: cs)
                pending.removeAll()
                pendingCharset = nil
            }
        }

        for m in word.matches(in: raw, range: NSRange(location: 0, length: ns.length)) {
            let between = ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let charset = ns.substring(with: m.range(at: 1)).components(separatedBy: "*")[0]
            let kind = ns.substring(with: m.range(at: 2)).uppercased()
            let payload = ns.substring(with: m.range(at: 3))
            let bytes = kind == "B" ? TransferEncoding.decodeBase64(Array(payload.utf8)) : decodeQ(payload)
            let adjacent = pendingCharset != nil && between.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            if adjacent, let p = pendingCharset, p.caseInsensitiveCompare(charset) == .orderedSame {
                pending += bytes
            } else {
                flush()
                if !adjacent { out += between }
                pendingCharset = charset
                pending += bytes
            }
            last = m.range.location + m.range.length
        }
        flush()
        out += ns.substring(from: last)
        return out
    }

    private static func decodeQ(_ text: String) -> [UInt8] {
        let u = Array(text.utf8)
        var out = [UInt8]()
        var i = 0
        while i < u.count {
            let c = u[i]
            if c == UInt8(ascii: "_") {
                out.append(0x20); i += 1
            } else if c == UInt8(ascii: "="), i + 2 < u.count,
                      let v = UInt8(String(decoding: u[(i + 1)...(i + 2)], as: UTF8.self), radix: 16) {
                out.append(v); i += 3
            } else {
                out.append(c); i += 1
            }
        }
        return out
    }

    public static func isPlainAscii(_ text: String) -> Bool {
        text.unicodeScalars.allSatisfy { (32...126).contains($0.value) }
    }

    /// Plain ASCII passes through; anything else becomes UTF-8 Base64 encoded-words of at most 75
    /// characters each, split on character boundaries.
    public static func encode(_ text: String) -> String {
        if isPlainAscii(text) { return text }
        var words = [String]()
        let prefix = "=?UTF-8?B?"
        let maxPayloadBytes = 45
        var current = [UInt8]()
        for scalar in text.unicodeScalars {
            let chunk = Array(String(scalar).utf8)
            if current.count + chunk.count > maxPayloadBytes {
                words.append(prefix + Data(current).base64EncodedString() + "?=")
                current.removeAll()
            }
            current += chunk
        }
        if !current.isEmpty { words.append(prefix + Data(current).base64EncodedString() + "?=") }
        return words.joined(separator: "\r\n ")
    }

    /// A display name: quoted if it needs to be, encoded if it is not ASCII.
    public static func encodePhrase(_ name: String) -> String {
        if !isPlainAscii(name) { return encode(name) }
        let needsQuote = name.contains { "()<>[]:;@\\,.\"".contains($0) }
        guard needsQuote else { return name }
        return "\"" + name.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") + "\""
    }
}
