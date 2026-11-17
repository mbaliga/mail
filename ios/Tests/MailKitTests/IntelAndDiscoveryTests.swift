import XCTest
@testable import MailKit

final class IntelAndDiscoveryTests: XCTestCase {
    func testOtpFoundNearKeywordWithValidity() throws {
        let f = try XCTUnwrap(OtpExtractor.find(
            subject: "Your verification code",
            text: "Your verification code is 482913. It expires in 10 minutes.",
            fromName: "Acme <no-reply@acme.com>"))
        XCTAssertEqual(f.code, "482913")
        XCTAssertFalse(f.isMagicLink)
        XCTAssertEqual(f.service, "Acme")
        XCTAssertEqual(f.validMinutes, 10)
        XCTAssertGreaterThan(f.confidence, 0.5)
    }

    func testOtpIgnoresYearsOrderNumbersAndMailWithNoKeyword() {
        XCTAssertNil(OtpExtractor.find(subject: "Hello", text: "Copyright 2024 verify", fromName: nil))
        XCTAssertNil(OtpExtractor.find(subject: "Hello", text: "Your order 4829173 verify", fromName: nil))
        XCTAssertNil(OtpExtractor.find(subject: "Meeting", text: "Room 123456 at noon", fromName: nil))
    }

    func testMagicLinkWithoutCode() throws {
        let f = try XCTUnwrap(OtpExtractor.find(subject: "Welcome back", text: "Click the button below to sign in.", fromName: nil))
        XCTAssertNil(f.code)
        XCTAssertTrue(f.isMagicLink)
    }

    func testProviderLookupByDomainEmailAndMx() {
        XCTAssertEqual(Providers.forEmail("ada@icloud.com")?.id, "icloud")
        XCTAssertEqual(Providers.forEmail("ada@GMAIL.com ")?.id, "gmail")
        XCTAssertNil(Providers.forEmail("ada@example.org"))
        XCTAssertNil(Providers.forEmail("not-an-address"))
        XCTAssertEqual(Providers.forMx("mail.protection.outlook.com.")?.id, "m365")
        XCTAssertEqual(Providers.forMx("aspmx.l.google.com")?.id, "gmail")
    }

    func testProviderSettingsMatchTheAndroidTable() throws {
        let icloud = try XCTUnwrap(Providers.byId("icloud"))
        XCTAssertEqual(icloud.imap, ServerTemplate("imap.mail.me.com", 993, .sslTls))
        XCTAssertEqual(icloud.smtp, ServerTemplate("smtp.mail.me.com", 587, .startTls))
        XCTAssertEqual(icloud.username(for: " ada@icloud.com "), "ada@icloud.com")
        // TLS is never downgraded: every shipped IMAP template except the local Proton Bridge is implicit TLS.
        for p in Providers.all where p.id != "proton" {
            if let imap = p.imap { XCTAssertEqual(imap.security, .sslTls, p.id) }
        }
    }

    func testIOSSignInGatingIsHonest() {
        XCTAssertTrue(Providers.icloud.signInSupportedOnIOS)     // app password over IMAP
        XCTAssertTrue(Providers.yahoo.signInSupportedOnIOS)
        XCTAssertFalse(Providers.outlook.signInSupportedOnIOS)   // OAuth-only: not ported yet
        XCTAssertFalse(Providers.hey.signInSupportedOnIOS)       // no IMAP at all
        XCTAssertFalse(Providers.fastmail.signInSupportedOnIOS)  // JMAP: not ported yet
    }

    func testNoModelEngineIsTheHonestDefault() async {
        let e = NoModelEngine()
        XCTAssertEqual(e.locality, .onDevice)
        do { _ = try await e.complete(prompt: "hi", maxTokens: 8); XCTFail("must not invent output") }
        catch { XCTAssertEqual(error as? LlmError, .noModelInstalled) }
    }
}
