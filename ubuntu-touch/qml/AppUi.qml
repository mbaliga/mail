// SPDX-License-Identifier: UNLICENSED
// Two screens: sign-in (setup) and a read-only inbox. All logic lives in the Rust core behind `backend`
// (providerFor / findOtp / fetchInbox); this file only lays out what the core says.
// Plain QtQuick + Controls 2 on purpose: no Lomiri import, so it lints and runs headless off-device.
// Lomiri.Components (Suru style, page headers) is a follow-up that only a device can validate.
import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12

Item {
    id: root
    // The Q_OBJECT facade (cpp/mailbackend.h) or a test double with the same surface.
    property var backend
    property alias page: stack.currentIndex   // 0 = setup, 1 = inbox
    property var messages: []
    property string account: ""

    // Palette: colour is never the only signal (every state also has a glyph and a word).
    readonly property color ink: "#1b1b1f"
    readonly property color paper: "#f6f5f1"
    readonly property color accent: "#3b3bd6"
    readonly property color muted: "#5c5c66"

    // Implicit onFoo handlers on purpose: the `function onFoo()` form needs Qt 5.15, UT 20.04 ships 5.12.
    Connections {
        target: root.backend
        onInboxLoaded: {
            root.messages = messages
            setup.error = ""
            stack.currentIndex = 1
        }
        onInboxFailed: setup.error = error
    }

    StackLayout {
        id: stack
        anchors.fill: parent

        SetupPage {
            id: setup
            backend: root.backend
            onSignIn: {
                root.account = user
                root.backend.fetchInbox(host, port, security, user, password, 50)
            }
        }
        InboxPage {
            account: root.account
            messages: root.messages
            backend: root.backend
            onSignOut: {
                root.messages = []
                root.account = ""
                stack.currentIndex = 0
            }
        }
    }
}
