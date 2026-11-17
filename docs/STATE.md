# STATE - You've got Mail

Living index. Newer than README where they differ. Last updated 2026-11-17.

## Platforms
| Platform | State | Evidence | Next |
|---|---|---|---|
| Android | shipping (see README) | JVM gate + Robolectric renders in CI; device behaviour owner-verified | - |
| iOS | **early scaffold**: Swift core (MailKit) + SwiftUI shell + CI lane | core tests: CI-LANE-WRITTEN (not run here); `Net/` + SwiftUI app: CODE-ONLY; device: NEEDS-DEVICE-VALIDATION | first CI run, then plan "Not ported yet" list; owner follow-ups in `docs/PORTING_PLAN.md` |
| Ubuntu Touch | not started | - | decide core strategy (see plan) |

iOS plan, drift ledger and owner actions: `docs/PORTING_PLAN.md`. Device checklist: `ios/docs/OWNER_VERIFY.md`.

## Rules (from README and the owner's constellation rulings)
No telemetry; on-device first; cloud opt-in and marked; keys only in the OS secret store; TLS never downgraded;
native ports only; honest uncertainty about anything not run on a device.
