# Working on Herdr for Android

For any agent (Claude or otherwise) changing this app. Read
`docs/ROADMAP.md` first: roles, process, priorities and decisions.

## Ground rules

- **This repository is the only copy** (the `android/` folder of
  Javamomma/herdr-app-android). Work on a branch, open a pull request
  to `master` with green CI. No changes kept only on a phone, a Mac or in a
  chat. The engineering lead (a Claude session) reviews and merges.
- **Herdr 2, not herdr.** The app talks to **herdr-core** (`hc connect` over
  SSH, framed JSON-RPC: `protocol/`). It is not a client of the herdr terminal
  multiplexer's socket API. Don't add a second protocol.
- **No GPL code** (Terminal Spike, Herdr Mobile, …): ideas are fine, code is
  not. The app is Apache-2.0.
- **Keep the core small.** Prefer what herdr-core already offers (`terminal.*`,
  `doc.*`, `machine.exec`, `file.read`) over new machinery on the Mac.
- **Commits:** lowercase conventional, one line (`feat: …`, `fix: …`).

## Layout

- `protocol/`: plain Kotlin, JVM-tested. `CoreClient` (framing, calls, streams),
  `EventLines` (event stream), `Layout` (the shared `herdr-app` document and
  its split tree), `Prompts` (reading an agent's question), `DemoCore` (a
  pretend core for the demo and tests).
- `terminal/`: plain Kotlin VT engine (`Emulator`), keys, paste, mouse. JVM-tested.
- `app/`: Android. `Session.kt` is all state and every change; `term/` the
  terminal view; `ui/` Compose screens; `Alerts.kt` notifications, the
  background service and the widget; `ssh/` connection and keys.

## Check before a pull request

```sh
./gradlew :protocol:test :terminal:test   # fast, no Android SDK needed
./gradlew :app:assembleDebug              # needs the Android SDK (CI does it)
```

New logic that doesn't need Android goes in `protocol/` or `terminal/` with a
test. UI changes: make sure the demo still shows them (`DemoCore`), since CI
takes screenshots of the demo on Fold-sized screens (`scripts/screenshots.sh`)
and, on pull requests that touch code, runs `scripts/safety-check.sh` on the
same emulator.

## Verify

Base branch: master

```sh
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew --no-daemon :protocol:test :terminal:test
```

The Mac mini has JDK 21 from Homebrew but no Android SDK; the APK build is
checked by the `android` GitHub Actions workflow.

## Design notes (why it is the way it is)

From the 2026-11-12 repair build. Read before changing these paths.

- **Send (Type box)** goes through the core's `terminal.send` (bare Enter:
  `terminal.keys`), not the terminal stream, because only an RPC gets an
  answer. `sendMessage` (`protocol/Send.kt`) maps it to `SendOutcome`:
  reply = Accepted, refusal or nothing written = Rejected, timeout or lost
  link after writing = Unknown. The draft is cleared only on Accepted and
  nothing is resent. Rejected: acking stream writes (flushed to SSH is not
  delivered).
- **Answer buttons** carry the question's `mark` to `Session.choose`, which
  re-reads the screen and sends only when `Prompts.pick` sees the same mark;
  the notification path (`answerShown`) uses the same mark. Risky options and
  every option of a dangerous question need a hold.
- **The mark** (`Prompts.question`) hashes, without whitespace, the last 6
  lines above the options (8 with none), extended up to the prompt's own box
  or rule, at most 40 lines. Only box-drawing lines count as rules. Rejected:
  a fixed 40 lines (the app reads only the visible screen, so keyboard,
  fold and resize would change the mark and refuse real answers). Known gap:
  an unboxed command longer than the cover is hashed only at its tail.
- **Committed keyboard text** is a paste only when a line break is followed
  by more text (`Keys.commitsAsPaste`); many keyboards commit Enter as "\n".
- **Left panel**: `LeftPanel` (`protocol/`) decides what the button and Back
  do. Hidden/shown on the wide layout is a phone preference
  (`view` prefs, `sidebarHidden`), never part of the shared layout document.
  The drawer's swipe-to-open is off so sideways drags stay with the terminal.
- **Scrollback lines are trimmed**: `Line.text(col)` returns a blank past the
  end; use `Emulator.wordAt` for word selection.
- **Terminal selection** (`Select`, `Sel`, `Clip.limit` in `terminal/`): the bar has Copy,
  Select all, Paste, Clear; no Cut (the terminal is not editable text). Select all runs from
  the oldest scrollback line to the last non-blank row (alternate screen: the screen only).
  A drag that starts within ~28 dp of a finished selection's end moves that end; a touch
  there that does not move is still a tap and clears. Copies are held to `Clip.MAX_CHARS`
  (200,000 characters, ~400 KB as UTF-16, under Android's ~1 MB transaction limit), keeping
  the newest text, with a notice. Bar Paste and key-row Paste share `clipboardText`; the key
  row still pastes into the draft only while the message box has focus.
- Decisions and user-facing notices that need tests live in `protocol/` and
  `terminal/` (`LeftPanel`, `SendOutcome`, `Clip`), since `app/` has no tests.

## Report back

In the pull request description:

1. What it does for the person using it, in a sentence or two.
2. What you changed, file by file, briefly.
3. How you checked it: tests run, on a device or not, screenshots.
4. Anything unfinished, risky, or that needs a decision.

Keep a pull request to one thing. Draft until it builds.
