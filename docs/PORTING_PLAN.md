# Porting plan - iOS and Ubuntu Touch

Status date: 2026-11-17. There was no porting-plan branch for this repo, so this file is derived
from an audit of `main` plus the owner's request of 2026-11-17 (Apple bundle ids are
`com.asoc.<appname>`; builds for macOS/iOS/Windows/Ubuntu Touch run on GitHub Actions).

## Evidence classes used below
`BUILT-HERE` ran in the Linux container and passed - `CI-LANE-WRITTEN` a workflow exists, never run -
`CODE-ONLY` written, never compiled - `NEEDS-DEVICE-VALIDATION` only the owner on hardware can say.

## Constraints that bind this port
- **Native port only.** No Waydroid/emulation. An iOS app is SwiftUI + Swift; it is not "the Android app running".
- **No telemetry, no phoning home.** The only exception is Apple's own TestFlight/App Store crash
  reporting, which Apple collects from users who opted in on their device; it is named in `ios/README.md`.
- **On-device first; cloud opt-in per task and a visibly "watched object"; BYOK keys only in the OS secret store.**
  iOS: Keychain, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, never synced.
- **TLS is never silently downgraded.** `ImapConnection` refuses anything but implicit TLS.
- Bundle id **`com.asoc.youvegotmail`**, written in ONE place: `ios/Config/Base.xcconfig`
  (it matches the Android rename already on `origin/claude/default-store-listings-u991hc`).
- Signing material never in the repo; CI actions SHA-pinned.

## Decision: Swift core + SwiftUI shell, not Kotlin Multiplatform

The audit's first recommendation was to convert `:mail-core` to KMP and ship an XCFramework.
Rejected for now, for concrete reasons:

1. `:mail-core` is `kotlin("jvm")` and leans on `java.net.Socket`, `javax.net.ssl`, okhttp, `java.util.zip`,
   `javax.xml`, `java.time`. Kotlin/Native compiles none of these; that is an `expect/actual` rewrite
   of the transport, JMAP, discovery and MIME layers - not a build-script change.
2. Kotlin/Native **Apple targets cannot be compiled on Linux**, so none of that rewrite could be
   verified in this container; it would all be `CODE-ONLY`.
3. Switching the module to KMP renames its test task (`:mail-core:test` becomes `jvmTest`) and would
   put the green Android gate (`:mail-core:test`, `:mail-ai:test`) at risk for no verifiable gain.
4. The SQLite store depends on `shared-libraries` (`search-core`), which would also have to go KMP.

Instead: `ios/` holds **MailKit**, a Swift package re-implementing the pure-logic slice of the core
(Foundation only, so it builds and tests on Linux), plus an Apple-only `Net/` layer on Network.framework
and a thin SwiftUI app. Test vectors are lifted from the Kotlin tests so the two cores answer alike.
The cost is two implementations to keep in step; the table below is the drift ledger.
If the owner later wants a single core, KMP remains open - the Swift ports are small enough to retire.

## Port ledger (Kotlin source -> Swift)

| Area | Kotlin (`:mail-core`) | Swift (`ios/Sources/MailKit`) | Evidence |
|---|---|---|---|
| Charsets, base64, quoted-printable, RFC 2047 | `mime/Codecs.kt` | `Mime/Codecs.swift` | CODE-ONLY + tests written (CI-LANE-WRITTEN) |
| IMAP response grammar (literals, sections) | `imap/ImapProtocol.kt` | `Imap/ImapValue.swift` (buffer parser, async-friendly) | same |
| Modified UTF-7, sequence sets, quoting | `imap/ImapProtocol.kt` | `Imap/ImapSupport.swift` | same |
| ENVELOPE, dates, message ids | `imap/FetchModel.kt`, `mime/` | `Imap/Envelope.swift` | same |
| OTP / magic-link finder | `intel/Otp.kt` | `Intel/Otp.swift` | same |
| Provider table (subset: 8 featured + GMX, Posteo, HEY) | `discovery/Providers.kt` | `Discovery/Providers.swift` | same |
| LLM seam | `:mail-ai` `LlmEngine` | `AI/LlmEngine.swift` (`NoModelEngine` only) | same |
| TLS IMAP connection (LOGIN, SELECT, FETCH ENVELOPE) | `net/`, `imap/ImapClient.kt` | `Net/ImapConnection.swift` (Network.framework) | CODE-ONLY |
| Keychain secrets | `security/KeystoreSecret` (app) | `App/Sources/KeychainStore.swift` | CODE-ONLY |

### Not ported yet (honest gaps, in suggested order)
1. MIME body parser + HTML-to-text + reading a message (inbox lists envelopes only; OTP runs on subject only).
2. SQLite store + FTS5 search + the shared query language (needs `search-core` ported or bound; GRDB or bundled SQLite).
3. SMTP send, outbox, undo-send; drafts.
4. STARTTLS, IDLE, CONDSTORE/QRESYNC, COMPRESS, Gmail X-GM extensions; POP3; JMAP (Fastmail).
5. OAuth sign-in (Gmail, Outlook, Microsoft 365) via `ASWebAuthenticationSession` - needs owner-registered client ids.
6. Rules engine, selection, snooze, automation, digest, Sieve export.
7. Background sync (`BGAppRefreshTask`), local notifications (permission requested after first account, as on Android).
8. On-device model: Core ML / MLX / llama.cpp behind `LlmEngine`; cloud BYOK engines with the "watched object" mark and task allow-list.
9. Hyle design system on iOS (Hyle is Compose; the shell is plain SwiftUI today; provenance marks not drawn).
10. Remaining ~17 providers from `Providers.kt`; autodiscovery (MX/SRV/autoconfig XML) with `Foundation` + `Network`.

## Build and CI
`.github/workflows/ios.yml` (`CI-LANE-WRITTEN`): Linux `swift test` in a `swift:6.0.3-noble` container;
`macos-latest` `swift test` + XcodeGen (pinned, sha256-verified) + `xcodebuild test` on the simulator, unsigned;
tag-only (`ios-v*`) signed archive behind the `ios-release` environment. Private repo => hosted macOS minutes bill.

## Owner follow-ups (cannot be done from the container)
1. Add the `com.asoc.youvegotmail` row to NAMES.md (rule R11) - NAMES.md is outside this repo.
2. Apple Developer Program enrolment; App ID; Distribution certificate; App Store provisioning profile.
3. Create the `ios-release` GitHub environment with required reviewers and secrets
   `IOS_CERT_P12_BASE64`, `IOS_CERT_PASSWORD`, `IOS_PROFILE_BASE64`, `IOS_PROFILE_NAME`, `IOS_TEAM_ID`.
4. First CI run: expect compile fixes in `Net/` and the SwiftUI app (never compiled against an Apple SDK).
5. Device verification: `ios/docs/OWNER_VERIFY.md`.
6. Export-compliance answer (`ITSAppUsesNonExemptEncryption: false` is set on the belief that TLS via the OS is exempt).
7. Register OAuth clients for Google/Microsoft if sign-in-with-provider is wanted on iOS.

## Ubuntu Touch (early port, built here)
Status date: 2026-11-17. Lives in `ubuntu-touch/`; lane `.github/workflows/ubuntu-touch.yml`.

### Decision: Rust core + Qt/QML shell
Options weighed (the audit's list), with the concrete reason for the pick:
1. **Kotlin/Native `linuxArm64` core** - rejected for the same reason as KMP on iOS: `:mail-core` is JVM-only
   (`java.net`, `javax.net.ssl`, okhttp, `javax.xml`), so it would be a rewrite that the Android gate cannot protect.
2. **Kotlin core on a bundled JRE as a localhost sidecar** - rejected: Ubuntu Touch has no JRE in the confined runtime;
   shipping one is large, and the result would be "the Android core in a wrapper", closer to the Waydroid-style
   substitute the owner ruled out than to a native port.
3. **Rust core + Qt/QML shell (chosen).** Native on the platform's own toolkit; the portable slice is re-implemented in
   Rust with vectors lifted from the Kotlin tests (the Swift core uses the same ones), and it **builds and tests on
   this Linux container**, which is the only thing that makes it verifiable here. Cost: a third implementation to keep
   in step (drift ledger below). Rust over C++ for memory safety in a parser fed by hostile network bytes.
4. Swift core reuse - not possible: no Swift toolchain on Ubuntu Touch devices.

### Port ledger (Kotlin -> Rust, `ubuntu-touch/core/src`)
| Area | Kotlin (`:mail-core`) | Rust | Evidence |
|---|---|---|---|
| Charsets, base64, QP, RFC 2047 | `mime/Codecs.kt` | `mime.rs` (encoding_rs for charsets) | BUILT-HERE (cargo test) |
| IMAP response grammar, UTF-7, UID sets, quoting | `imap/ImapProtocol.kt` | `imap.rs` | BUILT-HERE |
| ENVELOPE, message ids, RFC 5322 dates | `imap/FetchModel.kt` | `envelope.rs` | BUILT-HERE |
| OTP / magic link | `intel/Otp.kt` | `otp.rs` (fancy-regex for look-around) | BUILT-HERE |
| Providers (11 of the Kotlin table) | `discovery/Providers.kt` | `providers.rs` | BUILT-HERE |
| LOGIN / EXAMINE / FETCH, implicit TLS only | `net/`, `imap/ImapClient.kt` | `client.rs` (rustls+ring, generic stream) | protocol logic BUILT-HERE against scripted streams; the real TLS socket path is CODE-ONLY (never connected to a server) |
| C ABI + Qt facade + QML UI | - | `ffi.rs`, `cpp/`, `qml/` | native shell links, loads the QML and passes `--selftest` offscreen: BUILT-HERE; QML UI logic: BUILT-HERE (QtTest, test double); rendering/touch: NEEDS-DEVICE-VALIDATION |
| click packaging | - | `clickable.yaml`, `manifest.json.in`, apparmor, desktop | clickable.yaml validates against Clickable 8.10.0's own schema; the click itself was NEVER built (no Docker daemon): CI-LANE-WRITTEN |

Differences from the Swift port, on purpose: the Rust core also ships an RFC 5322 date parser that does not need
Foundation, and `client.rs` is generic over `Read + Write` so the whole session logic is tested without a network.
Known drift: charset coverage follows `encoding_rs` (a superset of the Swift list); date zone names are limited to
UT/UTC/GMT/Z and the US zones.

### Not ported yet (Ubuntu Touch; same list as iOS plus platform items)
Message bodies (MIME tree, HTML to text), SQLite store + FTS5 search (`search-core` needs a Rust or C binding), SMTP send /
outbox / drafts, STARTTLS, IDLE, CONDSTORE, Gmail extensions, POP3, JMAP, OAuth (needs owner-registered client ids and a
web-auth surface), rules/Sieve/snooze, push and background sync (no WorkManager; the UT push service or a confined
daemon is undecided), notifications, on-device model (llama.cpp behind the `LlmEngine` seam, not started), Hyle on QML,
the other ~17 providers, armhf builds, Lomiri.Components / Suru styling.
Credential storage: no hardware-backed keystore on UT. The app keeps the password in memory per session; a persistent
store (libsecret via online-accounts, or an encrypted file with a user passphrase) is an owner decision.

### Owner follow-ups (Ubuntu Touch)
1. Confirm the click name `youvegotmail.asoc` and the maintainer string in `ubuntu-touch/manifest.json.in` (permanent once on OpenStore).
2. Create the `ut-release` GitHub environment (required reviewers). No secrets are used; clicks are unsigned.
3. First CI run: expect fixes in the `click` job (Rust install inside the Clickable image under QEMU, `tonistiigi/binfmt` tag, download-artifact SHA).
4. Device verification: `ubuntu-touch/DEVICE_CHECKLIST_UT.md`.
5. Decide credential persistence and the OAuth / JMAP / body-reading order for the next slices.
6. OpenStore submission (account, review, icon/screenshots) when the app is worth listing.
