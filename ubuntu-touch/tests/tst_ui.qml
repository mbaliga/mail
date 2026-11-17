// SPDX-License-Identifier: UNLICENSED
// Headless UI tests against a test double of MailBackend (same invokables/signals). The real backend
// + real QML are exercised end to end by `youvegotmail --selftest`.
import QtQuick 2.12
import QtTest 1.2
import "../qml"

TestCase {
    id: tc
    name: "AppUi"
    width: 360; height: 720
    when: windowShown
    visible: true

    QtObject {
        id: mock
        property bool busy: false
        property var calls: []
        signal inboxLoaded(var messages)
        signal inboxFailed(string error)
        function providerFor(email) {
            if (email.indexOf("@icloud.com") > 0)
                return { found: true, provider: { id: "icloud", name: "iCloud", signInSupported: true, auth: ["appPassword"],
                    setupSteps: ["step one"], appPasswordUrl: "https://example.test/ap",
                    imap: { host: "imap.mail.me.com", port: 993, security: "sslTls" } } }
            if (email.indexOf("@outlook.com") > 0)
                return { found: true, provider: { id: "outlook", name: "Outlook.com", signInSupported: false, auth: ["oauth2"], setupSteps: [] } }
            return { found: false }
        }
        function fetchInbox(host, port, security, user, password, limit) { calls = calls.concat([[host, port, security, user, password, limit]]) }
    }

    Component { id: uiComp; AppUi { backend: mock; width: 360; height: 720 } }

    function find(item, name) {
        if (item.objectName === name) return item
        for (var i = 0; i < item.children.length; i++) { var r = find(item.children[i], name); if (r) return r }
        return null
    }

    function test_flow() {
        var ui = createTemporaryObject(uiComp, tc)
        compare(ui.page, 0)
        var signIn = find(ui, "signIn"), email = find(ui, "email"), pw = find(ui, "password")
        verify(signIn && email && pw)
        verify(!signIn.enabled)

        // Unsupported provider: honest note (glyph + words), button stays disabled even with a password.
        email.text = "ada@outlook.com"; pw.text = "x"
        compare(find(ui, "providerNote").text.indexOf("✗ Outlook.com: not available"), 0)
        verify(!signIn.enabled)

        // Supported provider.
        email.text = "ada@icloud.com"
        verify(find(ui, "providerNote").text.indexOf("✓ iCloud") === 0)
        verify(signIn.enabled)
        signIn.clicked()
        compare(mock.calls.length, 1)
        compare(mock.calls[0].slice(0, 5), ["imap.mail.me.com", 993, "sslTls", "ada@icloud.com", "x"])
        compare(pw.text, "")                      // the field is cleared once the secret is handed over

        // A failure comes back and is shown; we stay on the setup page.
        mock.inboxFailed("sign-in refused: nope")
        compare(ui.page, 0)
        verify(find(ui, "error").text.indexOf("sign-in refused: nope") > 0)

        // Success goes to the inbox and lists messages with unread glyph, subject and OTP.
        mock.inboxLoaded([
            { uid: 2, seen: false, subject: "Your code", date: 1793714709, from: { display: "Acme" }, otp: "482913" },
            { uid: 1, seen: true, subject: "", date: null, from: null, otp: null } ])
        compare(ui.page, 1)
        var list = find(ui, "list")
        compare(list.count, 2)
        // Sign out clears state and returns to setup.
        find(ui, "signOut").clicked()
        compare(ui.page, 0)
        compare(ui.messages.length, 0)
    }

    function test_unknown_provider_asks_for_server_and_never_offers_plaintext() {
        var ui = createTemporaryObject(uiComp, tc)
        find(ui, "email").text = "ada@example.org"
        find(ui, "password").text = "x"
        var host = find(ui, "host")
        verify(host !== null)
        verify(host.visible)
        verify(!find(ui, "signIn").enabled)
        host.text = "imap.example.org"
        verify(find(ui, "signIn").enabled)
        find(ui, "signIn").clicked()
        compare(mock.calls[mock.calls.length - 1][2], "sslTls")
    }
}
