#!/usr/bin/env bash
# Everything that can run on a Linux box with no device: Rust core tests, QML lint, QML UI tests
# (offscreen), and the native shell self-test (real Rust core + real QML, offscreen).
# Needs: cargo, cmake, g++, qtbase5-dev qtdeclarative5-dev qtdeclarative5-dev-tools qml-module-qttest
#        qml-module-qtquick2 qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2
set -euo pipefail
cd "$(dirname "$0")/.."
export QT_QPA_PLATFORM=offscreen QT_QUICK_BACKEND=software LANG=C.UTF-8 LC_ALL=C.UTF-8
echo "== cargo test (core)"; cargo test --locked --manifest-path core/Cargo.toml
echo "== qmllint"; qmllint qml/*.qml tests/*.qml
echo "== qmltestrunner"; qmltestrunner -input tests -import qml -o -,txt
echo "== shell self-test"
B="${UT_BUILD_DIR:-$(mktemp -d)}"
cmake -S . -B "$B" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$B" -j"$(nproc)"
"$B/youvegotmail" --selftest
echo "ALL OK"
