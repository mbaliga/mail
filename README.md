# You've got Mail

An Android mail app for every provider — Gmail, iCloud, Outlook.com, Microsoft 365, Yahoo, AOL,
Fastmail, Proton (through Bridge), Zoho, GMX, Posteo, mailbox.org, your company's server, anything
that speaks **IMAP, POP3 or JMAP** — built around one idea: a phone should be able to do what a
desktop client does, and the things desktop clients still can't.

Home is a sentence:

> **You've got** · **298** new mail · *on Gmail ▾* · *since yesterday ▾*

The two underlined phrases are controls (which inboxes, since when), so the headline is always true
about the list beneath it. Under it, bold tabs (REPLIES, TICKETS, OTP, TRAVEL, …) and a white sheet
of one-line summaries — *who wants what* — written on the phone when it has a model, picked from the
message itself when it doesn't. The ear button reads it all aloud.

## What it does that phone mail apps don't

**Selection that scales.** Long-press selects; keep holding and drag to sweep a range. *Select all*
means **everything that matches**, not the 50 rows on screen — archive 12,000 newsletters in one go.
**Rules for selection** grow, trim, intersect or replace the selection by query: presets
("older than a year", "can unsubscribe", "with trackers"), "everything like this message" (same
sender, same domain, same list, same thread), or anything typed in the search language. Invert it.
See what's in it before acting: size, senders, date span, how many lists you could leave.

**One query language everywhere.** Search, saved views, home tabs, selection rules and automation
rules all use the same grammar (powered by the constellation's `search-core`):
`from: to: cc: domain: subject: body: filename: ext: in: label: account: is: has: category:`
`date: after: before: older_than: newer_than: size: larger: smaller: attachments: recipients: lang:`
plus `"phrases"`, `-negation`, `OR`, `(groups)` and `/regex/`. Mistakes are explained inline
("`frm:` isn't a field — did you mean `from:`?"). Plain sentences work too ("invoices from last
month with a pdf"), rewritten into the grammar on the device; a model can help with harder ones.
Search the servers for mail older than the phone holds. Ask a question and get an answer with its
sources.

**Rules that run on the phone** — on new mail, on a schedule ("archive newsletters after a week"),
or on demand — with a live count of what a rule matches *before* you save it. Describe a rule in a
sentence and a model writes it. Export them as **Sieve** for servers that filter themselves.

**And the rest of a desktop client:** conversations, every folder and label, move/label/categorise,
snooze, pin, mute, star, spam, delete forever, undo for everything, undo send, send later, follow-up
nudges ("remind me if nobody replies by Monday"), aliases and signatures per identity, attachments
in and out (open, share, save to Downloads, forward with attachments), `.eml` open/save, view source,
one-click unsubscribe (RFC 8058) and mailto: unsubscribe, sender-verification warnings, tracking
pixels stripped, remote images on request, `mailto:` and share-sheet handling, keyboard shortcuts,
and a two-pane layout on tablets and foldables.

## Intelligence: on the phone first, your own key second

| | Where it runs | What it's used for |
|---|---|---|
| **Heuristics** | everywhere, always | categories, OTP codes, one-line summaries, priority, schema.org cards (flights, orders, parcels) |
| **Gemini Nano** (ML Kit GenAI / AICore) | on the phone, on devices that have it | summaries in the "who wants what" voice, categories when unsure, quick replies, drafts, rewrite/proofread, dates & tasks, the spoken briefing |
| **A downloaded Gemma model** (MediaPipe LLM Inference) | on the phone, any recent device | the same, for phones without AICore. Not bundled: pick it from the catalogue in Settings → Intelligence and it downloads on demand (resumable, into private storage, deletable). Gemma is gated on Hugging Face, so the catalogue asks for a free token and a licence accepted on the model's page. A `.task` file you already have also works. |
| **Your cloud key** (BYOK) | the provider you choose | only the tasks you allow |

Cloud providers: Anthropic (Claude), OpenAI, Google Gemini, Mistral, Groq, OpenRouter, DeepSeek,
xAI, Together, and any OpenAI-compatible server — including Ollama or LM Studio on your own
computer. The key is sealed in the Android Keystore and only ever sent to that provider.

What a cloud model may see is the person's decision, task by task: by default only drafting,
rewriting, translation, search and rule-writing are allowed; summaries, categories, extraction and
the briefing stay on the phone. Finance, security and code mail never leave the device unless you
change it, and whole accounts can be excluded. Addresses, phone numbers, card numbers, IBANs, codes
and link parameters are replaced with placeholders before sending and restored in the answer. A
daily token budget stops a busy inbox from draining a key. Every model-written line carries Hyle's
provenance mark — a filled radium disc for *this phone*, a hollow cyan ring for *a cloud model*.

**Claude specifics.** Claude is called through Anthropic's official Java SDK. The default model is
`claude-opus-5-5` (pick any other, e.g. a smaller model for cheaper bulk work). Requests opt in to
the Claude API's **server-side refusal fallback** (`server-side-fallback-2026-07-01`), so a refused
answer is retried on a fallback model by the API rather than failing.

## Moving around, finding, attaching, and speed

- **Section switcher.** The hamburger opens a card stack: a compact label list on the left follows a vertical pager of gradient cards. Four levels: *Home* (how much mail you've got), *All accounts* (Inbox, Outbox, Sent, Drafts, Trash, Spam…), *Views* (your saved searches) and *Accounts* — pick one to drill into its own folders.
- **Search.** One grammar for everything (typed or natural language). Typeahead completes operators, people, folders, categories and dates; results show the matching snippet with the hit in bold; "Refine" chips are facets (sender, year, category, has files, unread) computed from the current results.
- **Attach by asking.** In compose, the mail icon opens a live search ("the conversation with Priya about the lease in March"). Attach the messages as `.eml`, quote them, or hand them to the writing assistant as background (sensitivity checks still apply). A just-taken screenshot or copied clipboard content is offered as a one-tap suggestion; the clipboard is only peeked at (type and age) until you accept, and screenshots need the optional photo permission (Play review may ask you to justify `READ_MEDIA_IMAGES`; the feature is off until you opt in).
- **Speed.** `PerfTest` seeds 100,000 messages and enforces budgets. Typical times on a JVM dev machine: list a folder ≈ 5 ms, 1,500 rows ≈ 19 ms, FTS word search ≈ 55 ms, natural language ≈ 1.5 ms. Totals are counted after the rows appear, so lists never wait on counting. Startup builds the database off the main thread, and a hand-written baseline profile is included (not measured on a device). Run `./gradlew :mail-core:test -Dmail.perf.n=100000`.

## Architecture

```
:mail-core   pure JVM — the mail engine
  net/        TLS transport (SNI + hostname verification, never silently downgraded), COMPRESS
  imap/       IMAP4rev1/rev2: CONDSTORE/QRESYNC, UIDPLUS, MOVE, ESEARCH, IDLE, LITERAL+, ID,
              ENABLE, COMPRESS=DEFLATE, XOAUTH2/OAUTHBEARER, Gmail X-GM-EXT-1 (labels, threads)
  smtp/       submission: STARTTLS or implicit TLS, XOAUTH2, 8BITMIME, SMTPUTF8
  jmap/       RFC 8620/8621 + EventSource push, upload/download, EmailSubmission
  pop3/       with STLS, UIDL, TOP
  mime/       parser and builder: RFC 2047/2231, flowed text, every charset Java knows,
              HTML-to-text, sanitiser, tracker detection, JSON-LD
  discovery/  presets for 29 providers, autoconfig, Thunderbird ISPDB, DNS SRV/MX over HTTPS
  store/      SQLite (bundled, with FTS5) — reader/writer connections, change-driven flows
  sync/       incremental sync on each protocol's own cursor, offline action queue with undo,
              outbox with undo-send and scheduled send, Sent-copy reconciliation
  search/     the query language → SQL + FTS5 + residual matching
  select/     query-backed selections, selection rules, stats
  rules/      the rule engine, dry runs, Sieve export
  intel/      heuristic categoriser, summariser, OTP extractor, structured data
  digest/     the home sentence and its tabs
:mail-ai     pure JVM — the intelligence router, prompts, BYOK engines, redaction, budget
  :ondevice-runtime  Android library: the MediaPipe-backed local model engine (only place MediaPipe is used)
  :ondevice          Play on-demand module that carries :ondevice-runtime (with -PsplitOnDevice=true)
:app         Android: Compose UI in Hyle's material language on cell-shell's spatial shell,
             WorkManager jobs, push service, notifications, Keystore, OAuth, on-device engines
```

Shared code comes from the constellation as pinned git submodules composed with `includeBuild`:
`hyle-design-system` (tokens, provenance contract, Hyle Grotesk) and `shared-libraries`
(`search-core`, `cell-shell` — SpatialShell, WordWheelRail, EdgeTimelineScrubber, ShakeToRefresh —
`crash-recovery`, `feedback`).

**The shell.** Home never goes away; four rooms are parked off its edges: **Places** (left — every
folder, label and saved view on Hyle's word wheel), **Find** (right), **Rules** (top) and **Write**
(bottom: new message, drafts, outbox). Pages (a folder, a message, the composer, settings) open
over it.

## Sign-in setup

Most providers work with an app password and need no setup at all; the account screen shows the
exact steps for each (iCloud, Yahoo, AOL, Zoho, GMX, …).

**Gmail with Google sign-in** uses Google's on-device Authorization API (Play services): no client
secret and no backend. It needs the app registered once in a Google Cloud project:

1. Create a project at console.cloud.google.com, enable the **Gmail API**, and configure the OAuth
   consent screen with the scope `https://mail.google.com/`.
2. Create an **OAuth client ID** of type *Android* with package `dev.aarso.mail` and the SHA-1 of
   your signing key (`./gradlew :app:signingReport`).

Until then, Gmail works with an app password (Google Account → Security → 2-Step Verification →
App passwords).

**Microsoft (Outlook.com, Microsoft 365)** uses OAuth with PKCE (AppAuth). Register an app in
Entra ID (portal.azure.com → App registrations) as a public client with redirect URI
`dev.aarso.mail://oauth2redirect` and the delegated permissions `IMAP.AccessAsUser.All`,
`SMTP.Send` and `offline_access`, then put its client id in `local.properties`:

```
MICROSOFT_CLIENT_ID=00000000-0000-0000-0000-000000000000
```

(or the `MICROSOFT_CLIENT_ID` environment variable in CI). Microsoft 365 tenants must allow
authenticated SMTP for the mailbox.

**Fastmail** uses JMAP with an API token (Settings → Privacy & Security → Integrations).
**Proton Mail** works through Proton Mail Bridge on a computer on your network. **Tuta** and
**HEY** offer no standard access; the app says so instead of failing.

## Building

```bash
git submodule update --init --recursive
```

```bash
./gradlew :mail-core:test :mail-ai:test :app:testDebugUnitTest :app:assembleDebug
```

JDK 17 and an Android SDK with platform 36 are needed. `:app:testDebugUnitTest` also renders every
main screen on the JVM (Robolectric + Roborazzi) into `app/build/screens/` and boots the real
Application and Activity; CI uploads the screens and the debug APK. Robolectric downloads its Android
runtime from Maven Central on first run; behind a proxy or mirror, set `ROBOLECTRIC_REPO` to a Maven
repository URL that serves it.

No model weights are in the app. Nor, in Play builds, is the runtime that executes them.

- **Play bundle** (`./gradlew -PsplitOnDevice=true :app:bundleRelease`): MediaPipe's runtime (27 MB
  of native code) is the on-demand feature module `:ondevice`. The base install is about 40 MB
  uncompressed (arm64); Settings → Intelligence asks Play for the module the first time a local
  model is downloaded. CI builds this bundle and checks the runtime is not in the base module.
- **Every other build** (`assembleDebug`/`assembleRelease`, sideloads, emulators) carries the
  runtime in the base app, about 66 MB for release, so it just works without Play.

The app never links MediaPipe: `:ondevice-runtime` holds the engine, and the base app finds it by
name through the `LocalEngineFactory` interface in `:mail-ai`. Translation goes through whichever
model is available (downloaded, Gemini Nano, or your cloud key) rather than ML Kit Translate,
whose native library cannot be downloaded separately.

**Why these versions.** The constellation pins Kotlin 2.1, which fixes two choices: ML Kit's Prompt
API is at `1.0.0-beta2` (later betas need Kotlin 2.3+), and local models run on MediaPipe's LLM
Inference rather than its successor LiteRT-LM (which needs Kotlin 2.3 metadata and Java 21). Both
move up together when the pin does.

## Keyboard

`c` new message · `/` find · `g` places · `j`/`k` next/previous · `Enter` open · `x` select ·
`Shift+x` select range · `Ctrl+a` select all matching · `e` archive · `#` delete · `s` star ·
`Shift+i`/`Shift+u` read/unread · `r`/`a`/`f` reply/reply all/forward · `z` undo · `u`/`Esc` back.

## Privacy

Mail, credentials and model files stay on the phone; app data is excluded from cloud backup and
device transfer. Passwords, tokens and API keys are sealed with an AES-256-GCM key held in the
Android Keystore. Remote content is blocked by default and HTML is shown in a WebView with
JavaScript, file and content access off. Nothing is sent anywhere without an account or a key you
added; feedback is opt-in and is always a message you see in full and send yourself.

## Typeface

Hyle Grotesk Classic, under the SIL Open Font License (`FONT-LICENSE-HyleGroteskClassic.txt`).

### Provider logos
The strip on the add-account page uses vector marks for Gmail, iCloud, Outlook, Microsoft 365, AOL, Proton, Zoho, GMX, mail.com, Mail.ru, QQ, Orange, Naver, Tuta, HEY and AT&T, taken from the CC0 [Simple Icons](https://simpleicons.org) set. Brand names and logos belong to their owners. Providers without artwork yet (Yahoo, Fastmail, Yandex and others) show a coloured initial; drop a `provider_<id>.xml` vector into `res/drawable` to replace one.

### Icons
The interface icons are the supplied "You've got mail" set (Streamline, 48-unit grid, 3-unit strokes), compiled into `HyIcons` as vector paths: mail, mailbox, calendar, clock, image, settings, search, pen, share, send, plus, arrows, recycling (trash), reply (and forward, mirrored), info, alert, exclamation, exit and tools. Glyphs the set doesn't cover (star, archive, paperclip and so on) remain in the earlier Hyle-style drawing, with their stroke thinned to 1.5 so the weights match.

### Tabs and the thread carousel
Conversations, drafts and the outbox open in their own tabs along the foot of the screen (Inbox stays first). Tap to switch, tap the current tab's × or long-press any tab to close it; back from a tab's first page closes it. Opening the same message again switches to its tab. In a conversation, swipe sideways to move to the neighbouring threads of the list you came from (their edges peek in), and the thread's earlier messages are stacked above the page and below it as cards (details, share, reply, "See quoted text").

### Splash
There is one splash: the system window is plain navy (no icon), and the supplied artwork (`brand_splash.xml`, a vector, so sharp at any resolution) fades in over it for the first moments of a cold start.

### The home assistant (agentic, local first)
Home follows the supplied view: a black bar (back to Places, the assistant, Settings) over a white sheet with the headline sentence, tl;dr toggle, tabs and list. The sparkle in the bar opens the assistant: tell it what to do ("clear old newsletters", "what needs a reply?") or tap *Tidy up*. It looks through your mail with read-only tools (search, count, read), then lists **suggestions** — archive, mark read, star, snooze, label, draft a reply, make a rule — each ticked or unticked for you to review. Nothing happens until you press Apply; applied batches can be undone, drafts open in a tab and are never sent, rules open for review, and anything that trashes mail starts unticked.

Models run in order: an on-device model first; if it can't produce valid steps, your own key — only if you allowed *the home assistant* in Settings → Intelligence, never shown mail in a protected category or account, and with identifiers redacted. Every engine starts a fresh run, so nothing a local model read is replayed to a cloud one. Now and then (at most every six hours, and only on this phone) it quietly looks through what's new and leaves its suggestions on Home as a link; switch that off with `assistantAuto`.

### Search engineering around search-core
Parsing, the evaluator, scoring, natural-language rewriting, relative dates and facets all come from the shared `search-core`; the mail layer adds what a store needs around it:
- **Word breaking for every script.** search-core's `Segmenter` is the documented seam for scripts SQLite's tokenizer can't split (Chinese, Japanese, Thai, Lao, Khmer, Myanmar; Indic and pointed Arabic/Hebrew words it shatters). `IndexText` runs such text through it before indexing and rewrites query words the same way; the Android app injects the phone's own ICU breaker (`android.icu.text.BreakIterator`), which has the dictionaries the JDK lacks. Text in other scripts is indexed untouched, so snippets stay readable.
- **A versioned index that rebuilds itself.** The index records its format (scheme + word-breaker). When that changes, the store marks it for a background rebuild, done a few hundred messages at a time with search working meanwhile; Settings → Search shows progress and offers Check (SQLite's integrity check), Rebuild and Compact (FTS5 merge).
- **Recall.** HTML-only messages (most newsletters) are now indexed from their text, not just their preview.
- **Did you mean.** When a search finds almost nothing, words that match no message are corrected against the index's own vocabulary (`fts5vocab`: one edit, two for long words, swaps of neighbouring letters count as one), so it knows names and jargon.
- **Already there:** operators, boolean and regex, relative dates, relevance ranking with recency, typeahead, snippets, facets, saved views, server-side search for what isn't synced, query-backed select-all.
Not done: text inside attachments (PDF, Office files) is not indexed; only attachment names are.

## Licence

Source-available, free for noncommercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE) (SPDX: `PolyForm-Noncommercial-1.0.0`). Copyright 2026 Madhav Baliga. For commercial use, contact the copyright holder.
