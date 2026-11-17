import XCTest
@testable import MailKit

/// Vectors are lifted from mail-core's MimeTest.kt, so Kotlin and Swift are held to the same answers.
final class CodecsTests: XCTestCase {
    func testEncodedWordsDecodeAcrossCharsetsAndAdjacentWordsJoin() {
        XCTAssertEqual(EncodedWords.decode("=?UTF-8?B?R3LDvMOfZQ==?= aus =?iso-8859-1?Q?K=F6ln?="), "Grüße aus Köln")
        // RFC 2047 section 6.2: whitespace between two adjacent encoded words is dropped.
        XCTAssertEqual(EncodedWords.decode("=?UTF-8?B?R3LDvMOfZQ==?= =?iso-8859-1?Q?aus_K=F6ln?="), "Grüßeaus Köln")
        XCTAssertEqual(EncodedWords.decode("=?UTF-8?B?5pel?= =?UTF-8?B?5pys6Kqe?="), "日本語")
        XCTAssertEqual(EncodedWords.decode("plain text"), "plain text")
        XCTAssertEqual(EncodedWords.decode("Re: =?utf-8?q?=C3=9Cn=C3=AFcode?= tail"), "Re: Ünïcode tail")
    }

    func testEncodedWordEncodeRoundTripsLongNonAscii() {
        let s = "Ünïcödé subject that is long enough to need splitting into several encoded words — 日本語も"
        let enc = EncodedWords.encode(s)
        XCTAssertTrue(enc.components(separatedBy: "\r\n ").allSatisfy { $0.count <= 75 })
        XCTAssertEqual(EncodedWords.decode(enc.replacingOccurrences(of: "\r\n ", with: " ")), s)
    }

    func testPhraseQuotingAndAsciiPassthrough() {
        XCTAssertEqual(EncodedWords.encode("Plain Subject"), "Plain Subject")
        XCTAssertEqual(EncodedWords.encodePhrase("Lovelace, Ada"), "\"Lovelace, Ada\"")
        XCTAssertEqual(EncodedWords.encodePhrase("Ada Lovelace"), "Ada Lovelace")
    }

    func testBase64ToleratesLineBreaksJunkAndPaddingMidStream() {
        func dec(_ s: String) -> String { String(decoding: TransferEncoding.decodeBase64(Array(s.utf8)), as: UTF8.self) }
        XCTAssertEqual(dec("SGVs\r\nbG8s IFdv\ncmxkIQ=="), "Hello, World!")
        XCTAssertEqual(dec("QQ==Qg=="), "AB")
        XCTAssertEqual(dec("!!!"), "")
    }

    func testQuotedPrintableKeepsStrayEqualsAndDecodesSoftBreaks() {
        func dec(_ b: [UInt8]) -> String { String(decoding: TransferEncoding.decodeQuotedPrintable(b), as: UTF8.self) }
        XCTAssertEqual(dec(Array("a=b =\r\nc".utf8)), "a=b c")
        let original = String(repeating: "x", count: 100) + " ünï ="
        let qp = TransferEncoding.encodeQuotedPrintable(original)
        XCTAssertTrue(qp.components(separatedBy: "\r\n").allSatisfy { $0.count <= 76 })
        XCTAssertEqual(dec(Array(qp.utf8)), original)
    }

    func testBase64LinesAre76WideCRLF() {
        let out = TransferEncoding.encodeBase64Lines([UInt8](repeating: 0x41, count: 120))
        let lines = out.components(separatedBy: "\r\n").filter { !$0.isEmpty }
        XCTAssertEqual(lines.first?.count, 76)
        XCTAssertTrue(out.hasSuffix("\r\n"))
        XCTAssertEqual(TransferEncoding.decodeBase64(Array(out.utf8)), [UInt8](repeating: 0x41, count: 120))
    }

    func testWindows1252SmartQuotesAndNeverThrowing() {
        XCTAssertEqual(MailCharsets.decode([0x93, 0x68, 0x69, 0x94], charset: "iso-8859-1"), "\u{201C}hi\u{201D}")
        XCTAssertEqual(MailCharsets.decode([0x68, 0x69], charset: "x-no-such-charset"), "hi")
        XCTAssertEqual(MailCharsets.decode([], charset: "utf-8"), "")
        // Malformed UTF-8 is replaced, not fatal.
        XCTAssertTrue(MailCharsets.decode([0x68, 0xFF, 0x69], charset: "utf-8").contains("\u{FFFD}"))
    }
}
