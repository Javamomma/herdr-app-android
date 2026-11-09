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

- **One source of truth: this repository.** All work lands through a branch
  and a pull request with green CI. Work done in another Claude session (on
  the phone, the Mac or the web) goes on its own branch and PR here, never as
  a loose copy; Claude reviews and merges it.
- **Tests first where they're cheap:** the protocol and terminal engine are
  plain Kotlin with JVM tests; the app is checked by building and by the demo
  screenshots on Fold-sized emulators.
- **Releases** are tags (`vX.Y.Z`) with a CHANGELOG section; CI signs and
  publishes them. Test builds share one debug key and install over each other.
- **Commits:** lowercase conventional commits, one line.

## Where we stand (0.2.0)

Built: SSH to the Mac running `hc connect`; spaces, tabs and splits shared
live with the desktop app; agents list; notifications with Allow / Deny /
Reply; native terminal; Type box; saved commands; cover-screen agents view;
widget; app lock; network-change reconnect; demo mode; CI screenshots.

Waiting on the founder: the standalone repo, the release signing secrets, and
asking Can whether the name is fine (docs/LAUNCH.md).

## The landscape (November 2026)

Other Android apps for Herdr. All of them drive **herdr**, the terminal agent
multiplexer, through its socket API (`herdr pane …`, `herdr session …`). None
of them speak **herdr-core**, which the Herdr 2 desktop app runs on, so none
share the desktop app's spaces, tabs and splits.

| App | What it is | Notable |
|---|---|---|
| Herdr Mobile (`dev.herdr.mobile`, tuan3w/herdr-mobile, GPL-3.0, Flutter) | Agent board over SSH to many machines | One-tap answers parsed from the agent's question, risky answers need a hold; chat view over ACP; SFTP file viewer; stale-while-loading state; in-app updates; read-only terminal text that can't resize |
| Mussh (`app.mussh`, Coachlog Labs, paid unlock) | Separate chats per agent, Android and iPhone | 27 agents, approval cards, diffs; needs herdr for chat |
| Sherpr (F-Droid) | Herdr companion over SSH | Notifications, optional foreground service, no Google services |
| Moshi, Terminal Spike | SSH/Mosh terminals with herdr/tmux support | Mosh, tmux session pickers |

**What we have that they don't:** built for Herdr 2: the same spaces, tabs
and splits as the desktop window, live in both directions; a full
interactive terminal (real VT engine, resizes to the phone, mouse,
scrollback) instead of read-only text; layouts built for the Fold, inner and
cover screens; one connection reaches every machine the Mac has added;
Apache-2.0.

**Worth borrowing (as ideas, not code: theirs is GPL):**

1. Answer buttons read from the agent's actual question (its options), with
   hold-to-confirm for risky ones, instead of only Allow / Deny.
2. The last few lines of each agent's terminal on its card.
3. Show the last known state at once on launch, marked stale until fresh.
4. Tappable URLs and file paths in terminal output.
5. In-app update check against GitHub Releases.
6. Quick phrases and a slash-command palette in the Type box.

## Next, in order

1. Split into its own repo and ship 0.2.0 as a signed GitHub release.
2. Answer buttons from the agent's options (1), card previews (2), instant
   stale state (3).
3. Tappable links and paths (4); update check (5); phrases (6).
4. Space templates: start a space with a chosen program (e.g. TUIOS) from the
   New space dialog and saved commands.
5. F-Droid listing; then Google Play.

## Decisions

- 2026-11-07: Android client in Kotlin/Compose talking to herdr-core over SSH
  (`hc connect`); not a port of the GPUI desktop app.
- 2026-11-08: Own VT engine and Canvas terminal instead of xterm.js; ideas
  from Terminal Spike's notes, no GPL code.
- 2026-11-09: Predictive text stays in the Type box; raw terminal typing has
  no suggestions.
- 2026-11-09: Claude leads engineering and communications; the founder sets
  direction (this file).
