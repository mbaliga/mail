// SPDX-License-Identifier: UNLICENSED
#include "mailbackend.h"
#include <QCommandLineParser>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QTimer>
#include <QJsonDocument>
#include <QJsonObject>
#include <cstdio>

// `youvegotmail --selftest` loads the real QML against the real Rust core, exercises the C ABI, prints
// one line per check and exits non-zero on failure. It needs no network and no device; CI runs it
// offscreen. It proves the shell links and starts, NOT that anything renders correctly on a phone.
static int selfTest(QQmlApplicationEngine &engine, MailBackend &backend) {
    int failures = 0;
    auto check = [&](const char *name, bool ok) { std::printf("%s %s\n", ok ? "PASS" : "FAIL", name); failures += ok ? 0 : 1; };
    check("core version present", !backend.coreVersion().isEmpty());
    check("provider icloud", backend.providerFor("ada@icloud.com").value("provider").toMap().value("id") == "icloud");
    check("provider unknown", !backend.providerFor("ada@example.org").value("found").toBool());
    check("provider table", backend.providers().size() >= 10);
    check("otp found", backend.findOtp("Your verification code", "Your verification code is 482913.", "Acme").value("code") == "482913");
    check("qml root loaded", !engine.rootObjects().isEmpty());
    return failures;
}

int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);
    app.setApplicationName("youvegotmail.asoc");
    app.setOrganizationName("youvegotmail.asoc"); // confined data dir on Ubuntu Touch is keyed on the click name
    QCommandLineParser parser;
    QCommandLineOption selftest("selftest", "Run headless self-checks and exit.");
    parser.addOption(selftest);
    parser.parse(app.arguments());

    MailBackend backend;
    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty("mailBackend", &backend);
    engine.load(QUrl("qrc:/qml/Main.qml"));
    if (parser.isSet(selftest)) {
        const int failures = selfTest(engine, backend);
        // Let one event-loop turn run so QML bindings evaluate, then quit.
        QTimer::singleShot(200, &app, [&app, failures]() { app.exit(failures == 0 ? 0 : 1); });
        return app.exec();
    }
    if (engine.rootObjects().isEmpty()) return 1;
    return app.exec();
}
