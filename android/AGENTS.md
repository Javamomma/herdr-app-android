# Working on Herdr for Android

For any agent (Claude or otherwise) changing this app. Read
`docs/ROADMAP.md` first: roles, process, priorities and decisions.

## Ground rules

- **This repository is the only copy.** Work on a branch, open a pull request
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
takes screenshots of the demo on Fold-sized screens (`scripts/screenshots.sh`).

## Report back

In the pull request description:

1. What it does for the person using it, in a sentence or two.
2. What you changed, file by file, briefly.
3. How you checked it: tests run, on a device or not, screenshots.
4. Anything unfinished, risky, or that needs a decision.

Keep a pull request to one thing. Draft until it builds.
