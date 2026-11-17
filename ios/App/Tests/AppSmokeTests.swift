import XCTest
@testable import YouveGotMail

final class AppSmokeTests: XCTestCase {
    func testKeychainRoundTrip() throws {
        let key = "test.\(UUID().uuidString)"
        try KeychainStore.set("s3cret", for: key)
        XCTAssertEqual(KeychainStore.get(key), "s3cret")
        KeychainStore.delete(key)
        XCTAssertNil(KeychainStore.get(key))
    }

    func testAccountCodableCarriesNoPassword() throws {
        let a = Account(email: "a@icloud.com", username: "a@icloud.com", host: "imap.mail.me.com", port: 993,
                        providerName: "iCloud", secretKey: "acct.a@icloud.com")
        let json = String(data: try JSONEncoder().encode(a), encoding: .utf8)!
        XCTAssertFalse(json.lowercased().contains("password"))
    }
}
