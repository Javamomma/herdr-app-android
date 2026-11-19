# How we work, where we stand, what's next

The living plan for Herdr for Android. Every session (and every other Claude
helping) reads this first and updates it when something is decided.

## Roles

- **Founder (Arshia):** direction, ideas, priorities; anything that needs a
  person: accounts, secrets, money, posting in public under your name, talking
  to Can and the Herdr community.
- **Claude (engineering and communications lead):** design and code decisions,
  builds, CI, releases, monitoring and fixes, docs, marketing material and
  drafts. Merges its own work once CI is green on what it touched.

Claude asks first before anything that can't be undone or that speaks for you
in public: deleting things, rewriting history, publishing a release for the
first time under a new name, posting anywhere. Claude can't create repos,
set secrets, sign in to stores or post to Discord; those come to you as short,
exact steps.

## Process

- **One source of truth: this repository** (the `android/` folder of
  Javamomma/herdr-app-android). All work lands through a branch
  and a pull request with green CI. Work done in another Claude session (on
  the phone, the Mac or the web) goes on its own branch and PR here, never as
  a loose copy; Claude reviews and merges it.
- **Tests first where they're cheap:** the protocol and terminal engine are
  plain Kotlin with JVM tests; the app is checked by building and by the demo
  screenshots on Fold-sized emulators; the emulator job also runs
  `scripts/safety-check.sh` on pull requests that touch code.
- **Releases** are tags (`vX.Y.Z`) with a CHANGELOG section; releases are
  paused for now (see RELEASING.md). Test builds share one debug key and install over each other.
- **Commits:** lowercase conventional commits, one line.

## Where we stand (0.2.0)

Built: SSH to the Mac running `hc connect`; spaces, tabs and splits shared
live with the desktop app; agents list; notifications with Allow / Deny /
Reply; native terminal; Type box; saved commands; cover-screen agents view;
widget; app lock; network-change reconnect; demo mode; CI screenshots.

Releases are paused (RELEASING.md, docs/LAUNCH.md); there is no public
release.

## The landscape (November 2026)

The owner reports that the official Herdr app shipped on 2026-11-09/10 and
speaks herdr-core over SSH (not verified by the 2026-11-12 audit). This app
has no public release and is Arshia's own client, built for his Galaxy Z
Fold 7. The official app's public description, as the audit read it, is: see which agents need you, open the real terminal,
type, paste a screenshot, talk to it. Nobody has run it yet, so whether it
covers his terminal work, the Fold's two screens and safe answering is
unknown. Try it before investing in attachments, speech or new terminal
features here.

What this app keeps for itself: his queue (below), the two Fold layouts
(cover and inner), and answers from notifications.

Other Android apps (Herdr Mobile, Mussh, Sherpr, Moshi) drive the older
herdr socket API, not herdr-core. Their ideas were borrowed in 0.2.0.

## Next, in order

1. **Repair build**, tested on the phone: hide the left panel at every width
   (Back closes it); Paste beside Esc, going to the box you are typing in;
   no crash when long-pressing older terminal text; Copy with nothing
   selected leaves the clipboard alone; Send keeps the draft unless the
   message was delivered; answer buttons only answer the question that was
   on screen; unnamed tabs show their agent's title.
2. Pins, mark read/unread and a correct "seen" (shared `herdr.pinned` / `herdr.seen` labels, docs/labels.md): built, awaiting phone checks.
3. **Read-only Queue** (built, awaiting phone checks): an optional list of what is waiting on him, from the
   Mac's `panel.py queue` over the existing SSH session, with a stale / could
   not load state. Actions come later, one family at a time.
4. **Attach a photo** (built, awaiting phone checks): 📎 by the Type box (Android photo picker) and a Share target;
   shrunk, uploaded with core `terminal.put`, path inserted into the draft. Needs core 0.1.21.
5. Small reading and reply improvements (wrapped, selectable file viewer;
   last reply on agent cards; the helper that supplies the last reply cuts it
   at 600 characters, so show it as an excerpt with a way to fetch the rest).
6. A private release signing key, and trying the CI build on the phone
   without the cable.

Considered and rejected (audit 2026-11-12): public release on F-Droid or
Google Play while the official app exists; AI summaries or any model in the
app; a rewrite in another toolkit or a different terminal engine; a general
panel format; replacing or splitting this app before trying the official one.
Deferred: a microphone button, a whole-conversation
view, HTML pages.

## Decisions

- 2026-11-07: Android client in Kotlin/Compose talking to herdr-core over SSH
  (`hc connect`); not a port of the GPUI desktop app.
- 2026-11-08: Own VT engine and Canvas terminal instead of xterm.js; ideas
  from Terminal Spike's notes, no GPL code.
- 2026-11-09: Predictive text stays in the Type box; raw terminal typing has
  no suggestions.
- 2026-11-09: Claude leads engineering and communications; the founder sets
  direction (this file).
- 2026-11-09: Other Claude sessions work through branches and pull requests
  here (AGENTS.md); the TUIOS space comes in that way.
- 2026-11-09: The app moved to its own repository, Javamomma/Herdr-Android,
  from the `android/` folder of the herdr-app fork (history kept).
- 2026-11-10: That repository is retired; the app is back in the `android/`
  folder of Javamomma/herdr-app-android, the only home. Pull requests go to
  `master`. Releases are paused.
- 2026-11-12: The official Herdr app exists and speaks herdr-core; no public
  release of this app (F-Droid and Google Play dropped). Next: repair build,
  then a read-only Queue.
