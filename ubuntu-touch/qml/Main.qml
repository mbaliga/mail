// SPDX-License-Identifier: UNLICENSED
// Entry point: binds the real backend (context property set in cpp/main.cpp) to the UI.
import QtQuick 2.12
import QtQuick.Window 2.12

Window {
    id: win
    visible: true
    width: 360
    height: 720
    title: "You've got Mail"
    color: "#f6f5f1"

    AppUi {
        anchors.fill: parent
        backend: mailBackend
    }
}
