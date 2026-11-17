# You've got Mail - iOS

Native SwiftUI app over **MailKit**, a Foundation-only Swift port of the portable parts of `:mail-core`.
Plan, ledger and owner follow-ups: [`../docs/PORTING_PLAN.md`](../docs/PORTING_PLAN.md).

**Status: early, unverified.** Nothing here has been compiled against an Apple SDK or run on an iPhone.
See the evidence labels in the plan. What the first build can do (once it compiles): sign in to a
provider that accepts an **app password over IMAP with implicit TLS** (iCloud, Yahoo, AOL, Gmail with an
app password, GMX, Posteo), show the newest 50 envelopes with an unread headline, and pick out
verification codes from subjects. No sending, no search, no store, no OAuth yet.

## Layout
```
Package.swift            MailKit package (iOS 17 / macOS 14); `swift test` runs on Linux too
Sources/MailKit/         Mime/ Imap/ Intel/ Discovery/ AI/   - pure Foundation
                         Net/                                - Network.framework, compiled only on Apple
Tests/MailKitTests/      vectors lifted from the Kotlin tests
App/Sources, App/Resources, App/Tests   SwiftUI app, privacy manifest, entitlements, app tests
Config/Base.xcconfig     bundle id + versions (the ONE place the identity lives)
project.yml              XcodeGen spec; the .xcodeproj is generated, never committed
docs/OWNER_VERIFY.md     what only a person with an iPhone can confirm
```

## Build
```
cd ios
swift test                       # core tests (Linux or macOS)
xcodegen generate                # macOS only
xcodebuild test -project YouveGotMail.xcodeproj -scheme YouveGotMail \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO
```
CI does the same on GitHub Actions: `.github/workflows/ios.yml`.

## Privacy
No analytics, no telemetry, no tracking SDKs; `PrivacyInfo.xcprivacy` declares no tracking and no
collected data. The password goes only to your mail provider over TLS and is stored in this device's
Keychain, not synced. **The one exception**: if you install through TestFlight or the App Store and have
turned on "Share With App Developers", Apple (not this app) collects crash reports and gives them to the
developer. The app contains no code that does this and nothing else leaves the phone.
