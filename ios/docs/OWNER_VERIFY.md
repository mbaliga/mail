# iOS - owner verification checklist

Everything below is `NEEDS-DEVICE-VALIDATION`. Nothing has been run on an iPhone, iPad or simulator by
the author of this port. Tick a line only after you have seen it.

## Build
- [ ] The `iOS` workflow's `ios-macos` job compiles and passes (expect first-run compile fixes in `Net/`
      and `App/Sources`, which have never met an Apple SDK).
- [ ] The Linux `swift-core-linux` job passes. If a charset test fails on Linux only (Windows-1252 /
      Foundation differences), the macOS result is the one that matters; adjust the Linux expectation.
- [ ] A tag `ios-v0.1.0` produces a signed `.ipa` (after the owner follow-ups in the plan).

## First run (iPhone, iOS 17+)
- [ ] Launch shows "You've got Mail" setup. Typing an iCloud address shows the app-password steps.
- [ ] Sign in to iCloud with an app password: succeeds, inbox lists recent mail, headline count matches the unread rows.
- [ ] A wrong password shows "iCloud refused the sign-in" and stores nothing (relaunch: back at setup).
- [ ] Gmail with an app password works; Outlook/Microsoft 365 explain that sign-in with the provider is not on iOS yet.
- [ ] Sign out: relaunch returns to setup; Settings does not list a leftover Keychain item (re-sign-in works).
- [ ] Airplane mode: refresh shows an error line, not a crash or an endless spinner.
- [ ] A message with a 6-digit code in the subject shows "Code: ......" under its row.
- [ ] Unread is distinguishable without colour (dot + weight). VoiceOver reads "Unread".
- [ ] Dynamic Type at the largest size does not clip rows.
- [ ] With a network proxy/inspector (e.g. Charles on your Mac) the only traffic is to your provider's IMAP host.

## Not expected to work yet
Reading a message, sending, search, rules, background sync, notifications, local model, OAuth, JMAP, POP3.
