# Handoff — You've got Mail

State as of branch `claude/rules-v2`, a draft PR stacked on PR #3 (branch `claude/optimistic-pascal-wht0oc`,
[mbaliga/mail#3](https://github.com/mbaliga/mail/pull/3)); PR #2 is merged into `main`.
CI (`build-test`) was green on every pushed commit. **Nothing has been run on a real phone.** Everything
below is verified by unit tests, Robolectric render tests (screens in `app/build/screens/`, uploaded as a CI
artifact) and CI only.

## 1. What it is
An Android mail app for IMAP, POP3 and JMAP providers (Gmail, iCloud, Outlook, 365, Yahoo, AOL, Fastmail, Proton
via Bridge, Zoho, GMX, Posteo, mailbox.org, custom servers…). Kotlin 2.1, Compose, minSdk 31, AGP 8.9.1.
On-device AI first, bring-your-own-key (BYOK) cloud second.

## 2. Repo layout
| Module | What | Runs on |
|---|---|---|
| `mail-core` | protocols, MIME, SQLite + FTS5 store, sync, search, selection, rules, digest, `.eml` | plain JVM |
| `mail-ai` | model router, BYOK engines, privacy/redaction, `MailAi`, **`MailAgent`** | plain JVM |
| `ondevice-runtime` | MediaPipe engine | Android |
| `ondevice` | Play on-demand feature module (only with `-PsplitOnDevice=true`) | Android |
| `app` | Compose UI, platform glue, workers | Android |
| `hyle-design-system`, `shared-libraries` | git submodules, composite builds (`cell-shell`, `search-core`, `crash-recovery`, `feedback`) | — |

Build/test (needs `ANDROID_HOME=/opt/android-sdk` in this container):
`./gradlew :mail-core:test :mail-ai:test :app:testDebugUnitTest`. Counts at handoff: mail-core 56, mail-ai 15, app 48.
Render tests write PNGs to `app/build/screens/`. Perf at 100k messages:
`./gradlew :mail-core:test --tests '*PerfTest*' -Dmail.perf.n=100000`.
CI builds the debug APK and a release bundle and uploads them with the screens as run artifacts.

## 3. What is done

**Foundations (earlier work)**
- Accounts for all providers, discovery, OAuth/app-password sign-in, sync engine with an op queue and outbox
  (undo send, send later, nudges), notifications, WorkManager, Keystore-held secrets.
- Search registry, query-backed "select all matching", selection rules, mail rules (+ Sieve export), categorizer,
  digest, tl;dr, OTP/structured-data extraction, TTS briefing.
- AI: router (on-device first, cloud only for allowed tasks), Anthropic/OpenAI/Gemini-style BYOK engines, redaction,
  daily token budget, never-cloud categories/accounts.
- Models are **downloaded on demand** (catalogue only in the APK); MediaPipe runtime is a Play on-demand module.

**Design system and pages**
- Page system (black header, white sheet, pinned footer action, "statement" screens) applied to every page.
- Splash: exactly one. System splash = plain navy window (no icon); the in-app splash is the supplied artwork as an
  exact **vector** (`res/drawable/brand_splash.xml`), faded out after ~0.9 s. Previous bitmap-traced splash removed.
- Supplied **icon set** (22 Streamline icons) compiled into `HyIcons`; remaining glyphs thinned to the same weight.
- Add-account page: real provider logos (Simple Icons, CC0) in a strip that drifts on its own and can be dragged.
  Providers without artwork (Yahoo, Fastmail, Yandex, others) show a coloured initial.

**Home (latest design)** — black bar (back → Places, assistant, Settings) over a white sheet: headline sentence,
tl;dr toggle, tabs, list.

**Home assistant (agentic, local first → BYOK)** — `mail-ai/.../MailAgent.kt`, `app/.../ai/Assistant.kt`, `ui/home/AssistantSheet.kt`
- Read-only tools (search, count, read); output is **proposals** (archive, trash, mark read, star, snooze, label,
  draft reply, make rule). Nothing runs until the user presses Apply; one undo per applied batch; drafts open
  in a tab and are never sent; rules open in the editor; trash starts unticked.
- Model protocol is one JSON object per turn (works on small local models and cloud). Engines tried in router order;
  each gets a fresh run. Cloud only if the user allowed task "the home assistant"; protected categories/accounts
  withheld from cloud, identifiers redacted. Background triage (≤ every 6 h) is **on-device only** (`assistantAuto`).
- 9 unit tests (scripted fake engines) cover loop, invented ids, fences/chatter, fallback, privacy, step cap.

**Navigation**
- **Section switcher** (hamburger/back → Places): gradient card stack + label list; four levels — Home, All accounts,
  Views, Accounts → each account's own folders.
- **Tabs**: reading, drafts, outbox open in their own tabs at the foot; per-tab back stacks; same message re-opens its tab;
  tap/× /long-press to close. Lists/settings belong to the Inbox tab.
- **Thread carousel**: swipe between neighbouring threads (peeking edges); earlier messages stacked above the page and
  as overlapping cards (details, share, reply, "See quoted text").

**Search** (grammar, evaluator, scoring, natural language, dates, facets come from `shared-libraries/search-core`)
- Typeahead (`Suggest.kt`), snippets with bold hits, facet "Refine" chips, relevance ranking, async totals.
- Script-aware indexing: `IndexText` runs Chinese/Japanese/Thai/Lao/Khmer/Myanmar/Indic/pointed Arabic-Hebrew text through
  search-core's `Segmenter`; the app injects the phone's ICU breaker (`IcuBoundarySource`). Other text is indexed untouched.
- **Versioned index that rebuilds itself** in the background (`prepareIndex`/`reindexStep`), progress and
  Check/Rebuild/Compact under Settings → Search. HTML-only mail is now indexed from its text.
- **Did you mean** from the index vocabulary (`fts5vocab`, schema v3).
- Server-side search for unsynced mail ("Search servers too").

**Compose**
- Attach mail by natural language (live search; as `.eml`, quoted, or as context for the writing assistant).
- Screenshot and clipboard suggestions (clipboard only peeked at until accepted; screenshots need opt-in photo permission).

**Rules v2** (`claude/rules-v2`; details in its PR)
- `MailRule` extended, not replaced: every new field is defaulted, so rules saved before read back unchanged; schema v4 rewrites saved rules in place (test with a fixture of old rules and a version-3 database). Typed `Trigger`, `Condition` tree (all/any/not over Query, Header, AgeOver, SizeOver, ListId, IsReply, FromKnownContact, InCategory), `CopyTo`, `RunLocation`, forwarding that waits for one confirmation.
- Pure `RuleEngine.evaluate` and `conflicts` (judged on real mail in the editor); batch engine answers query leaves from the index and structural leaves in SQL; `preview`, `applyToExisting` with one undo, `RuleScheduler` plus a periodic worker that skips a round on Battery Saver.
- Server side: `SieveCompiler` (golden files per condition and action; refuses what a server would do differently, with the reason), a ManageSieve client (RFC 5804, STARTTLS required), JMAP Sieve (RFC 9661), `SieveSync` (never replaces a script it did not write; remembers which rules the server carries so nothing runs twice; takes rules back if the script is switched off). Gmail filters and Outlook rules are stubs with no network code.
- Editor as a sentence ("When mail arrives, if …, then …") with the Find typeahead, "Add condition", live "Matches N messages", where it runs and why, one-line conflicts, "Also apply to existing mail" checkbox.

**Performance** — schema v2 composite/covering indexes, partial index for placeholders, FTS rank join, async counts, off-main-thread
startup, hand-written baseline profile. Seeded 100k messages: list ≈ 5 ms, 1,500 rows ≈ 19 ms, FTS word ≈ 55 ms,
natural language ≈ 1.5 ms (JVM, not a phone).

## 4. Known gaps / not verified
1. **No device run.** Swipe/drift feel, tab bar ergonomics, assistant with a real on-device model, ICU segmentation on real
   CJK/Thai mail, first-launch re-index time, battery cost of background triage — all untested. The baseline profile is hand-written, unmeasured.
2. Attachment **contents** (PDF, Office) are not indexed — only file names. PDF needs a text-extraction library: a decision.
3. Assistant: applying several suggestions at once yields one undo (last batch). Small local models may not follow the JSON step format;
   it falls back to the BYOK key only if allowed.
4. Tabs: scroll positions are restored, but unsaved typing in a draft body is not (drafts autosave through the store).
5. Providers with no logo asset (see above). The Microsoft 365 mark is hand-drawn; others are Simple Icons.
6. `READ_MEDIA_IMAGES` may need a Play-policy justification; the feature is off until the user opts in.
7. `ScreensTest.rules` used to time out now and then. Likely cause, fixed: the test left the previous test's `-wal`/`-shm` files to be replayed onto the next test's new database. The suite passed 10 runs in a row afterwards (it failed about one run in four before); not proven, so watch it.
8. The thread-card view shows the focused message in the full reading view with its action bar; other messages are cards.
9. (Done) Settings has switches for the clipboard and screenshot suggestions and the assistant's background look.
10. PR #3 is still a draft; nothing has been merged since PR #2.

## 5. Suggested next steps
- Install the debug APK (CI artifact `app-debug` on the latest run) on a real phone and walk: first run → add account → home →
  assistant → open threads in tabs → Find with CJK mail → compose attach/suggestions.
- Add logos for the remaining providers.
- Measure the baseline profile on a device and regenerate it with a macrobenchmark.
- Decide on attachment text extraction.
- Mark PR #3 ready and merge once reviewed.

## 6. Conventions worth keeping
- Commits end with the `Co-Authored-By` and `Claude-Session` trailers; PR descriptions end with the generated-with line and session URL.
- Never put a model identifier in commits, PR text or code comments.
- Mail logic belongs in `mail-core`/`mail-ai` (JVM-testable); the app only renders and wires.
- Cloud use is opt-in per task and never for protected categories/accounts; keep `Redactor` in the path.

## 7. Everything pending (consolidated)

**Needs a phone (nothing below has been seen on a device)**
- Walk the full flow: first run → add account → Home → assistant → tabs/carousel → Find with CJK/Thai mail → compose attach and suggestions.
- Measure cold start; generate a real baseline profile (the current one is hand-written).
- First-launch re-index time on a large mailbox; battery cost of background triage; assistant against a real on-device model.
- Confirm Home's account phrase for several accounts reads "across N mailboxes" (only the single-account wording was rendered).
- Section switcher: only the Home and All-accounts levels were rendered; check Views and per-account folders.

**Product work not done**
- Attachment *content* search (PDF, Office): decision needed on a PDF text library; today only file names are indexed.
- Provider logos missing: Yahoo, Fastmail, Yandex, Posteo, mailbox.org, Xfinity, Free, Libero, Seznam, NetEase, WEB.DE, T-Online.
- Icons from the supplied set not yet placed anywhere: alert, exclamation, exit, tools, the curved down-arrow and the inbound arrow.
- Open tabs are in memory only: they are lost if Android kills the process, and unsaved typing in a draft body is not restored.
- Assistant: one undo per applied batch (not per suggestion); drafts can't be edited before applying; no run history; can't unsubscribe or move to a folder; make-rule opens the rule editor prefilled with a real rule (it is saved only when you press Save); triage runs only when Home is opened, not on a WorkManager schedule.
- Thread view: the focused message keeps the full reading view rather than becoming a card; reader titles that wrap to two lines crowd the header buttons.
- Search: "did you mean" keeps the first letter (slips there aren't caught) and appears only in Find; field weights in relevance ranking are untuned; the in-memory matcher used for mixed OR queries still uses the JDK word breaker, not ICU.
- Perf not yet great: from+has+older ≈ 120 ms, facets ≈ 160 ms (both while typing), deep paging ≈ 100 ms (not used by the UI).
- Rules v2 needs a real server: the ManageSieve client and JMAP Sieve were tested against an in-process fake server and a mock HTTP server only. mailbox.org (`imap.mailbox.org:4190`) and Posteo (`posteo.de:4190`) are taken from the providers' documentation as remembered, not verified here; Fastmail is reached over JMAP Sieve, and whether it also offers ManageSieve is unverified.
- Rules v2: copy-to-folder is carried out on this phone only for label-style accounts (Gmail, JMAP); on folder-style IMAP accounts it works only through a server script (`fileinto :copy`). A device-side IMAP COPY op was not added because it needs a sync-layer design (placeholder rows, undo of a copy) that was not specified.
- Rules v2: a rule's Header condition can only test headers the store keeps (from, to, cc, bcc, reply-to, sender, subject, list-id, list-unsubscribe, message-id, in-reply-to, references, authentication-results).
- Rules v2: the app has no screen yet for "your server already runs a script of its own — replace it?"; `SieveSync` supports the takeover (`allowTakeover`), the editor only reports it.
- Rules v2: no way yet to see or edit the generated server script; export as Sieve (Rules room) shows the same compile.

**Process**
- Draft PR #3 is unreviewed and unmerged.
- `ScreensTest.rules` flake: probable cause fixed (stale WAL files), 10 clean runs; keep an eye on CI.
- CI result for the handoff commit itself not yet seen (docs only).
- Play: justification for `READ_MEDIA_IMAGES`. Release signing and store listing have not been worked on.
