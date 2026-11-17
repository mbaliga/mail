# ubuntu-touch/ - You've got Mail for Ubuntu Touch (early port)

A **native** Ubuntu Touch app (Qt/QML shell over a Rust core), not Waydroid and not the Android app in a wrapper.
It is an **early port**: sign in to an IMAP account with an app password and read the newest 50 envelopes of INBOX
(read-only). Most of the Android app is not here; the list is in `docs/PORTING_PLAN.md`, "Ubuntu Touch".

```
core/                 Rust crate `mailcore` (std + rustls/ring + encoding_rs + fancy-regex + serde_json)
  src/mime.rs         charsets, base64/QP, RFC 2047        <- mail-core/mime/Codecs.kt
  src/imap.rs         IMAP grammar, modified UTF-7, UID sets <- mail-core/imap/ImapProtocol.kt
  src/envelope.rs     ENVELOPE, message ids, RFC 5322 dates  <- mail-core/imap/FetchModel.kt
  src/otp.rs          one-time-code / magic-link finder      <- mail-core/intel/Otp.kt
  src/providers.rs    provider table (11 of the Kotlin entries)
  src/client.rs       LOGIN / EXAMINE / FETCH over implicit TLS (rustls), generic over Read+Write
  src/ffi.rs          C ABI, JSON strings (mailcore_free to release)
  tests/              vectors lifted from the Kotlin tests (same ones the Swift core uses)
cpp/                  Qt facade: MailBackend (QObject) + main.cpp (`--selftest` mode)
qml/                  Main.qml (window), AppUi.qml (state), SetupPage.qml, InboxPage.qml; plain QtQuick + Controls 2
tests/tst_ui.qml      QtTest against a test double of MailBackend
scripts/run-tests.sh  everything that can run on a Linux box
manifest.json.in, youvegotmail.apparmor (policy group: networking only), youvegotmail.desktop, clickable.yaml
DEVICE_CHECKLIST_UT.md  what only a person with an Ubuntu Touch phone can confirm
```

## Rules this port keeps
* No telemetry, no analytics, no phoning home. The only network connection is to the user's own IMAP server.
* TLS is never downgraded: `connect_tls` accepts implicit TLS only (STARTTLS and plaintext return `InsecureTransportRefused`,
  also through the C ABI). Credentials containing line breaks are refused before anything is sent.
* The password is **not persisted**: it is typed per session, handed to the core, cleared from the text field, and the
  Qt worker overwrites its byte copy. (Ubuntu Touch has no hardware-backed keystore comparable to Android Keystore;
  an encrypted on-disk credential store is a deliberate follow-up, not a quiet default.)
* AppArmor policy asks for `networking` and nothing else. No cloud model, no account relay.

## Run the checks (Linux, no device)
```
sudo apt-get install cmake g++ qtbase5-dev qtdeclarative5-dev qtdeclarative5-dev-tools \
  qml-module-qttest qml-module-qtquick2 qml-module-qtquick-controls2 qml-module-qtquick-layouts \
  qml-module-qtquick-window2 libgl1 libegl1          # plus a Rust toolchain (rustup)
ubuntu-touch/scripts/run-tests.sh
```
Builds the Rust core, runs its tests, lints QML, runs the QML tests offscreen, builds the native shell and runs
`youvegotmail --selftest` (real core + real QML, no network).

## Build the click (needs Docker; normally CI: `.github/workflows/ubuntu-touch.yml`)
```
python3 -m venv .venv && .venv/bin/pip install clickable-ut==8.10.0
cd ubuntu-touch && ../.venv/bin/clickable build --arch arm64 && ../.venv/bin/clickable review --arch arm64
```
`clickable install` / `clickable desktop` are for the owner's machine. The first image build installs Rust
(`image_setup` in `clickable.yaml`); under QEMU (arm64 on an x86 host) expect it to be slow.

## Owner placeholders
* Click name `youvegotmail.asoc` and `maintainer: OWNER-FILL <...>` in `manifest.json.in`: **confirm both**. The click
  name is permanent once published to OpenStore. (The Apple bundle id is `com.asoc.youvegotmail`; UT click names have
  a different shape, `<appname>.<maintainer-id>`, so `asoc` is a guess at the maintainer id.)
