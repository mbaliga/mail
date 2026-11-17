import XCTest
@testable import MailKit

/// Vectors are lifted from mail-core's ImapTest.kt.
final class ImapTests: XCTestCase {
    private func parseAll(_ s: String) throws -> [ImapResponse] {
        var buf = Array(s.utf8)
        var out = [ImapResponse]()
        while !buf.isEmpty, let (r, used) = try ImapResponseParser.parse(buf) {
            out.append(r)
            buf.removeFirst(used)
        }
        XCTAssertTrue(buf.isEmpty, "unconsumed bytes left over")
        return out
    }

    func testStatusResponsesCarryCodesAndText() throws {
        let rs = try parseAll("* OK [CAPABILITY IMAP4rev1 IDLE AUTH=XOAUTH2] Gimap ready\r\nY1 NO [TRYCREATE] No such mailbox\r\n")
        guard case .status(let tag, let kind, let code, let text) = rs[0] else { return XCTFail("not a status") }
        XCTAssertNil(tag)
        XCTAssertEqual(kind, "OK")
        XCTAssertEqual(code?.name, "CAPABILITY")
        XCTAssertEqual(code?.values.map { $0.text }, ["IMAP4rev1", "IDLE", "AUTH=XOAUTH2"])
        XCTAssertEqual(text, "Gimap ready")
        guard case .status(let t2, let k2, let c2, _) = rs[1] else { return XCTFail("not a status") }
        XCTAssertEqual(t2, "Y1"); XCTAssertEqual(k2, "NO"); XCTAssertEqual(c2?.name, "TRYCREATE")
        XCTAssertFalse(rs[1].isOk)
    }

    func testFetchWithLiteralSectionAndEnvelope() throws {
        let header = "From: a@b.c\r\nSubject: Hi\r\n\r\n"
        let wire = "* 12 FETCH (UID 345 FLAGS (\\Seen $Forwarded) INTERNALDATE \"03-Nov-2026 14:05:09 +0000\" RFC822.SIZE 4021 "
            + "ENVELOPE (\"Tue, 3 Nov 2026 14:05:09 +0000\" \"=?UTF-8?Q?Caf=C3=A9?=\" ((\"Ren\\\"ee\" NIL \"renee\" \"example.com\")) NIL NIL "
            + "((NIL NIL \"me\" \"example.org\")) NIL NIL \"<parent@x>\" \"<abc@example.com>\") "
            + "BODY[HEADER.FIELDS (FROM SUBJECT)] {\(header.utf8.count)}\r\n\(header))\r\n"
        let rs = try parseAll(wire)
        guard case .data(let number, let keyword, let values) = rs[0] else { return XCTFail("not data") }
        XCTAssertEqual(number, 12); XCTAssertEqual(keyword, "FETCH")
        let items = values[0].items
        func value(for key: String) -> ImapValue? {
            var i = 0
            while i + 1 < items.count { if items[i].text?.uppercased() == key.uppercased() { return items[i + 1] }; i += 2 }
            return nil
        }
        XCTAssertEqual(value(for: "UID")?.long, 345)
        XCTAssertEqual(value(for: "FLAGS")?.items.compactMap { $0.text }, ["\\Seen", "$Forwarded"])
        XCTAssertEqual(value(for: "RFC822.SIZE")?.long, 4021)
        let env = try XCTUnwrap(Envelope.parse(try XCTUnwrap(value(for: "ENVELOPE"))))
        XCTAssertEqual(env.subject, "Café")
        XCTAssertEqual(env.from.count, 1)
        XCTAssertEqual(env.from[0].address, "renee@example.com")
        XCTAssertEqual(env.from[0].name, "Ren\"ee")
        XCTAssertEqual(env.to.first?.address, "me@example.org")
        XCTAssertEqual(env.inReplyTo, "parent@x")
        XCTAssertEqual(env.messageId, "abc@example.com")
        XCTAssertNotNil(env.date)
        // The literal (which contains CRLF) came through intact as the section's bytes.
        XCTAssertEqual(value(for: "BODY[HEADER.FIELDS (FROM SUBJECT)]")?.bytes.map { String(decoding: $0, as: UTF8.self) }, header)
    }

    func testListEsearchAndVanishedParse() throws {
        let rs = try parseAll(
            "* LIST (\\HasNoChildren \\Sent) \"/\" \"[Gmail]/Sent Mail\"\r\n"
            + "* ESEARCH (TAG \"Y5\") UID ALL 1:3,7,10:11\r\n"
            + "* VANISHED (EARLIER) 41,43:45\r\n")
        guard case .data(_, "LIST", let listValues) = rs[0] else { return XCTFail("not LIST") }
        XCTAssertEqual(listValues[2].text, "[Gmail]/Sent Mail")
        guard case .data(_, "ESEARCH", let es) = rs[1] else { return XCTFail("not ESEARCH") }
        XCTAssertEqual(es.last?.text, "1:3,7,10:11")
        XCTAssertEqual(SequenceSet.parse(try XCTUnwrap(es.last?.text)), [1, 2, 3, 7, 10, 11])
        guard case .data(_, let k, _) = rs[2] else { return XCTFail("not data") }
        XCTAssertEqual(k, "VANISHED")
    }

    func testIncompleteInputAsksForMoreBytesAndNeverGuesses() throws {
        // A literal announced but only partly delivered, then the rest arrives.
        let full = "* 1 FETCH (BODY[TEXT] {5}\r\nhello)\r\n"
        let bytes = Array(full.utf8)
        for cut in [3, 10, bytes.count - 5, bytes.count - 1] {
            XCTAssertNil(try ImapResponseParser.parse(Array(bytes[0..<cut])), "cut at \(cut) must be incomplete")
        }
        let (r, used) = try XCTUnwrap(try ImapResponseParser.parse(bytes))
        XCTAssertEqual(used, bytes.count)
        guard case .data(_, "FETCH", let v) = r else { return XCTFail("not FETCH") }
        XCTAssertEqual(v[0].items[1].bytes, Array("hello".utf8))
    }

    func testContinuationAndPipelinedResponsesConsumeExactly() throws {
        let rs = try parseAll("+ go ahead\r\n* 3 EXISTS\r\nA1 OK done\r\n")
        XCTAssertEqual(rs.count, 3)
        XCTAssertEqual(rs[0], .continuation("go ahead"))
        XCTAssertEqual(rs[1], .data(number: 3, keyword: "EXISTS", values: []))
        XCTAssertTrue(rs[2].isOk)
    }

    func testModifiedUtf7RoundTrips() {
        for s in ["Entwürfe", "日本語", "A&B", "Sent Items", "Корзина", "~peter/mail/台北/日本語"] {
            XCTAssertEqual(ModifiedUtf7.decode(ModifiedUtf7.encode(s)), s)
        }
        XCTAssertEqual(ModifiedUtf7.encode("日本語"), "&ZeVnLIqe-")
        XCTAssertEqual(ModifiedUtf7.encode("A&B"), "A&-B")
    }

    func testSequenceSetsCompressAndChunk() {
        XCTAssertEqual(SequenceSet.of([10, 9, 7, 3, 2, 1]), "1:3,7,9:10")
        XCTAssertEqual(SequenceSet.chunked(Array(1...1200), maxPerChunk: 500).count, 3)
        XCTAssertEqual(SequenceSet.of([]), "")
        XCTAssertEqual(SequenceSet.parse(":"), [])
    }

    func testQuotingAndLiteralDecision() {
        XCTAssertEqual(ImapQuote.quote("a\"b\\c"), "\"a\\\"b\\\\c\"")
        XCTAssertTrue(ImapQuote.needsLiteral("a\nb", utf8: true))
        XCTAssertTrue(ImapQuote.needsLiteral("é", utf8: false))
        XCTAssertFalse(ImapQuote.needsLiteral("é", utf8: true))
    }
}
