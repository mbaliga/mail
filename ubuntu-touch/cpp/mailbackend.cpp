// SPDX-License-Identifier: UNLICENSED
#include "mailbackend.h"
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPointer>
#include <QtConcurrent>

extern "C" {
void mailcore_free(char *);
char *mailcore_version();
char *mailcore_providers();
char *mailcore_provider_for_email(const char *);
char *mailcore_find_otp(const char *, const char *, const char *);
char *mailcore_fetch_inbox(const char *, int, const char *, const char *, const char *, int);
}

namespace {
// Takes ownership of a core string and parses it as JSON.
QJsonDocument take(char *p) {
    if (!p) return {};
    const QByteArray bytes(p);
    mailcore_free(p);
    return QJsonDocument::fromJson(bytes);
}
} // namespace

MailBackend::MailBackend(QObject *parent) : QObject(parent) {}

QString MailBackend::coreVersion() const {
    return take(mailcore_version()).object().value("core").toString();
}

QVariantMap MailBackend::providerFor(const QString &email) const {
    return take(mailcore_provider_for_email(email.toUtf8().constData())).object().toVariantMap();
}

QVariantList MailBackend::providers() const {
    return take(mailcore_providers()).array().toVariantList();
}

QVariantMap MailBackend::findOtp(const QString &subject, const QString &text, const QString &fromName) const {
    return take(mailcore_find_otp(subject.toUtf8().constData(), text.toUtf8().constData(),
                                  fromName.toUtf8().constData())).object().toVariantMap();
}

void MailBackend::fetchInbox(const QString &host, int port, const QString &security,
                             const QString &user, const QString &password, int limit) {
    if (m_busy) return;
    m_busy = true;
    emit busyChanged();
    QPointer<MailBackend> self(this);
    // Copies into the worker; the password lives only in this lambda's QByteArray for the call.
    QtConcurrent::run([=]() {
        QByteArray h = host.toUtf8(), s = security.toUtf8(), u = user.toUtf8(), p = password.toUtf8();
        const QJsonObject r = take(mailcore_fetch_inbox(h.constData(), port, s.constData(), u.constData(),
                                                        p.constData(), limit)).object();
        p.fill('\0');
        if (!self) return;
        QMetaObject::invokeMethod(self.data(), [self, r]() {
            if (!self) return;
            self->m_busy = false;
            emit self->busyChanged();
            if (r.value("ok").toBool())
                emit self->inboxLoaded(r.value("messages").toArray().toVariantList());
            else
                emit self->inboxFailed(r.value("error").toString());
        }, Qt::QueuedConnection);
    });
}
