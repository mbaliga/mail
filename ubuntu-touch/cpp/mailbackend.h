// SPDX-License-Identifier: UNLICENSED
// QObject facade over the Rust core (libmailcore). All heavy work stays in the core; this class only
// marshals JSON <-> QVariant and keeps blocking network calls off the GUI thread.
#pragma once
#include <QObject>
#include <QString>
#include <QVariant>

class MailBackend : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(QString coreVersion READ coreVersion CONSTANT)
public:
    explicit MailBackend(QObject *parent = nullptr);
    bool busy() const { return m_busy; }
    QString coreVersion() const;

    // {found: bool, provider: {...}} for an address (e.g. "ada@icloud.com").
    Q_INVOKABLE QVariantMap providerFor(const QString &email) const;
    // The provider table (list of maps) for the account picker.
    Q_INVOKABLE QVariantList providers() const;
    // {found, code, isMagicLink, service, validMinutes, confidence}
    Q_INVOKABLE QVariantMap findOtp(const QString &subject, const QString &text, const QString &fromName) const;
    // Asynchronous; emits inboxLoaded or inboxFailed. The password is used for this call only and
    // is not stored by this class. Implicit TLS only: the core refuses anything else.
    Q_INVOKABLE void fetchInbox(const QString &host, int port, const QString &security,
                                const QString &user, const QString &password, int limit);

signals:
    void busyChanged();
    void inboxLoaded(const QVariantList &messages);
    void inboxFailed(const QString &error);

private:
    bool m_busy = false;
};
