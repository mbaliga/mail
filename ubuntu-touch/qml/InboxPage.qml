// SPDX-License-Identifier: UNLICENSED
import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12

Item {
    id: page
    property var backend
    property string account: ""
    property var messages: []
    signal signOut()

    function dateText(unix) {
        if (unix === null || unix === undefined) return ""
        return Qt.formatDateTime(new Date(unix * 1000), "d MMM, HH:mm")
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 16
        spacing: 8

        RowLayout {
            Layout.fillWidth: true
            Label { text: "Inbox"; font.pixelSize: 24; font.bold: true; Layout.fillWidth: true }
            Button { text: "Sign out"; objectName: "signOut"; onClicked: page.signOut() }
        }
        Label {
            Layout.fillWidth: true
            elide: Text.ElideMiddle
            color: "#5c5c66"
            text: page.account + "  ·  " + page.messages.length + " newest, read-only"
        }

        ListView {
            id: list
            objectName: "list"
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: page.messages
            spacing: 4
            delegate: Rectangle {
                width: list.width
                height: row.implicitHeight + 16
                color: "#ffffff"
                border.color: "#d8d6cf"
                ColumnLayout {
                    id: row
                    x: 8; y: 8
                    width: parent.width - 16
                    spacing: 2
                    RowLayout {
                        Layout.fillWidth: true
                        // Unread = filled dot + bold + the word; read = hollow dot. Not colour alone.
                        Label { text: modelData.seen ? "○" : "●"; color: "#3b3bd6" }
                        Label {
                            Layout.fillWidth: true
                            elide: Text.ElideRight
                            font.bold: !modelData.seen
                            text: modelData.from ? modelData.from.display : "(unknown sender)"
                        }
                        Label { color: "#5c5c66"; font.pixelSize: 12; text: page.dateText(modelData.date) }
                    }
                    Label {
                        Layout.fillWidth: true
                        elide: Text.ElideRight
                        font.bold: !modelData.seen
                        text: (modelData.subject && modelData.subject.length > 0) ? modelData.subject : "(no subject)"
                    }
                    Label {
                        visible: modelData.otp !== null && modelData.otp !== undefined
                        text: "🔑 Code: " + modelData.otp
                        font.family: "monospace"
                        color: "#3b3bd6"
                    }
                    Label { visible: !modelData.seen; text: "unread"; color: "#5c5c66"; font.pixelSize: 11 }
                }
            }
            Label {
                anchors.centerIn: parent
                visible: page.messages.length === 0
                text: "Inbox is empty"
            }
        }
    }
}
