# Device checklist - Ubuntu Touch (owner only)

Nothing below has been run by the author: the build container has no Ubuntu Touch device, no emulator and no
Docker daemon. Every item is **NEEDS-DEVICE-VALIDATION**. Tick with the date, device model and UT release.

## Install
- [ ] `clickable build --arch arm64` succeeds on your machine (or download the unsigned click from the CI run) and
      `clickable review` has no errors. Note any framework / policy warnings.
- [ ] `clickable install` puts it on the phone; the icon "You've got Mail" appears in the launcher.
- [ ] The app starts (no immediate crash). If it does, capture `clickable logs`.

## Sign-in (use a throwaway mailbox first)
- [ ] Typing `you@icloud.com` shows "✓ iCloud: supported" and the setup steps; `you@outlook.com` shows the
      "✗ ... not available in this port yet" note and the button stays disabled.
- [ ] Sign in with an iCloud / Yahoo / GMX app password lists the newest messages, newest first.
- [ ] A wrong password shows "Could not sign in" and does NOT echo the password anywhere.
- [ ] Unknown domain: manual host field appears; there is no way to choose plaintext.
- [ ] Airplane mode: a readable network error appears, the button re-enables (no stuck "Signing in...").
- [ ] Rotating / locking the screen mid-fetch does not lose state or crash.

## Look and feel (the author never saw the UI)
- [ ] Text fits at the phone's scale factor; the keyboard does not hide the password field.
- [ ] Unread rows show the filled dot, bold text and the word "unread"; read rows a hollow dot.
- [ ] An OTP email shows the "Code:" line (subject-only detection; the body is not fetched yet).
- [ ] Plain QtQuick Controls 2 look acceptable; decide whether to adopt `Lomiri.Components` (Suru) next.

## Confinement
- [ ] Outbound connection to the mail server works under AppArmor with only the `networking` group.
- [ ] No file is written outside the app's confined directories; the password is not on disk
      (`grep -r` the app's data dir after signing out).
- [ ] No other network destination is contacted (check with a packet capture on the phone or router).

## Known limits to expect (see docs/PORTING_PLAN.md, "Ubuntu Touch")
No message bodies, no sending, no local store/search, no push/background sync, no OAuth (Gmail/Outlook), no JMAP,
no on-device model, no notifications, armhf (32-bit) not built.
