// SPDX-License-Identifier: UNLICENSED
import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12

Flickable {
    id: page
    property var backend
    property string error: ""
    signal signIn(string host, int port, string security, string user, string password)

    // What the core knows about the typed address. Re-evaluated as the user types.
    readonly property var lookup: backend ? backend.providerFor(email.text) : ({ found: false })
    readonly property var provider: lookup.found ? lookup.provider : null
    readonly property bool manual: !provider && email.text.indexOf("@") > 0
    readonly property bool busy: backend ? backend.busy : false
    readonly property bool canSignIn: password.text.length > 0 && !busy &&
        (provider ? provider.signInSupported : (manual && host.text.length > 0 && Number(port.text) > 0))

    contentWidth: width
    contentHeight: col.height + 32
    clip: true

    ColumnLayout {
        id: col
        x: 16
        y: 16
        width: page.width - 32
        spacing: 12

        Label { text: "You've got Mail"; font.pixelSize: 28; font.bold: true; color: "#1b1b1f" }
        Label {
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            color: "#5c5c66"
            text: "Sign in with an app password over IMAP (implicit TLS). Nothing is sent anywhere except your mail server. No analytics."
        }

        TextField {
            id: email
            Layout.fillWidth: true
            placeholderText: "Email address"
            inputMethodHints: Qt.ImhEmailCharactersOnly | Qt.ImhNoPredictiveText
            objectName: "email"
        }

        // Provider verdict: a glyph AND a word, never colour alone.
        Label {
            visible: provider !== null
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            objectName: "providerNote"
            text: provider ? (provider.signInSupported
                      ? "✓ " + provider.name + ": supported (" + provider.auth.join(" / ") + ")"
                      : "✗ " + provider.name + ": not available in this port yet. " +
                        (provider.unsupportedReason ? provider.unsupportedReason
                         : "It needs sign-in with the provider (OAuth) or JMAP, which are not ported."))
                  : ""
        }
        Repeater {
            model: provider && provider.signInSupported ? provider.setupSteps : []
            delegate: Label {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                color: "#5c5c66"
                text: (index + 1) + ". " + modelData
            }
        }
        Label {
            visible: provider && provider.signInSupported && provider.appPasswordUrl
            Layout.fillWidth: true
            wrapMode: Text.WrapAnywhere
            color: "#3b3bd6"
            text: provider && provider.appPasswordUrl ? "App password page: " + provider.appPasswordUrl : ""
        }

        // Unknown domain: enter the server by hand. Implicit TLS only; there is no "plain" option.
        ColumnLayout {
            visible: manual
            Layout.fillWidth: true
            Label { text: "Unknown provider: enter your IMAP server (TLS, usually port 993)"; wrapMode: Text.Wrap; Layout.fillWidth: true }
            TextField { id: host; Layout.fillWidth: true; placeholderText: "imap.example.org"; objectName: "host" }
            TextField { id: port; Layout.fillWidth: true; placeholderText: "993"; text: "993"; inputMethodHints: Qt.ImhDigitsOnly; objectName: "port" }
        }

        TextField {
            id: password
            Layout.fillWidth: true
            placeholderText: "App password"
            echoMode: TextInput.Password
            inputMethodHints: Qt.ImhSensitiveData | Qt.ImhNoPredictiveText
            objectName: "password"
        }
        Label {
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            color: "#5c5c66"
            text: "The password is kept in memory for this session only. It is not saved to disk (Ubuntu Touch has no hardware-backed keystore to put it in)."
        }

        Button {
            id: go
            objectName: "signIn"
            Layout.fillWidth: true
            text: busy ? "Signing in…" : "Sign in"
            enabled: canSignIn
            onClicked: {
                var h = provider ? provider.imap.host : host.text
                var p = provider ? provider.imap.port : Number(port.text)
                var sec = provider ? provider.imap.security : "sslTls"
                page.signIn(h, p, sec, email.text.trim(), password.text)
                password.text = ""    // do not keep the secret in a text field after handing it over
            }
        }
        Label {
            visible: page.error.length > 0
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            objectName: "error"
            color: "#1b1b1f"
            font.bold: true
            text: "⚠ Could not sign in: " + page.error
        }
    }
}
