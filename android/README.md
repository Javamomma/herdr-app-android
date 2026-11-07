# Herdr for Android

A Herdr client for Android phones and foldables, tuned for the Galaxy Z Fold 7.
It connects to **herdr-core on your Mac** over SSH and shows the same spaces,
tabs, splits and agents as the desktop app there. Terminals keep running on the
Mac when the phone sleeps, loses signal, or the app closes.

```
phone ──ssh──▶ Mac mini: hc connect ──▶ herdr-core (terminals, agents, layout)
                                         ▲
                     Herdr desktop app ──┘  (same spaces, live)
```

## What it does

- **Spaces, tabs, splits**: read from and written to each machine's shared
  layout document (`doc.get`/`doc.set` `herdr-app`), the one the desktop app
  uses. Make a space, open tabs, split right or down, drag dividers, rename,
  close: the desktop window follows, and the other way round.
- **Agents**: every terminal that reports a status, sorted blocked → done →
  working → idle, with status dots on spaces and tabs. Tap one to jump to it.
  A worker without a pane opens in **peek**; *Keep as tab* gives it one.
  Looking at an agent marks it seen everywhere (`herdr.seen`), like the desktop.
- **Other machines**: machines added on the Mac (`hc machine add`) appear in
  the sidebar with their own spaces; their terminals run there.
- **New tab menu**: a terminal, any coding agent found on that machine
  (Claude Code, Codex, OpenCode, Gemini, Amp, Aider, …), or any command. Agents
  start in the account's login shell and leave a shell when they exit.
- **Files and Changes** panels for the focused terminal's folder: browse and
  read files, `git status` and diffs, *cd here*.
- **Terminals**: xterm.js with truecolor, mouse, scrollback, the core's first
  paint and history, credit-based flow control, and a key bar for Esc, Tab,
  sticky Ctrl/Alt, arrows, Home/End, PgUp/PgDn, `| ~ / - \``, paste, ^C and
  text size.

### Layout on the Fold

- **Unfolded** (inner screen, 600 dp and wider): spaces and agents sidebar
  beside the tab; splits shown as on the desktop. From 900 dp the Files/Changes
  panel sits beside the panes.
- **Folded** (cover screen): one column; the sidebar is a drawer (☰), and a
  split tab shows one pane at a time with a pane switcher.
- Folding and unfolding doesn't restart anything: the SSH session, terminals
  and scroll positions carry over and the layout reflows.

### Not on the phone

Mods (the JavaScript panels in `~/.config/herdr-app/mods`) and layouts are
drawn by the desktop app's GPUI renderer, which doesn't run on Android. A mod
pane shows as a placeholder; this app has native Files and Changes panels
instead. Desktop keybindings (`keys.json`) don't apply; a hardware keyboard
types into the terminal.

## Set up the Mac mini

1. **Remote Login**: System Settings → General → Sharing → Remote Login: on.
2. **herdr-core**: installed with Herdr. Check in Terminal: `hc --version`
   (0.1.11 or newer).
3. **Reach it from outside your home network** (optional): install
   [Tailscale](https://tailscale.com) on the Mac and the phone, then use the
   Mac's Tailscale name (for example `mac-mini`) as the address.

The app runs `hc connect` over SSH. It first adds `~/.local/bin`,
`/opt/homebrew/bin` and `/usr/local/bin` to PATH, because SSH commands don't
load your shell profile. If `hc` lives elsewhere, change the command under
**Advanced** on the connect screen.

## Install on the phone

1. Download the APK: the **android** workflow in this repository's Actions tab
   builds one on every push (artifact `herdr-android-apk`). Or build it
   yourself (below).
2. Open it on the phone and allow installing from that source.

## Connect

1. Enter the Mac's address (`mac-mini.local`, its IP, or its Tailscale name)
   and your Mac user name (what `whoami` prints).
2. Pick how to sign in:
   - **This phone's key** (recommended): the app makes its own Ed25519 key,
     kept encrypted by the Android Keystore. Tap *Copy key* or *Share…* and
     add it on the Mac:
     ```sh
     echo 'ssh-ed25519 AAAA… herdr-android' >> ~/.ssh/authorized_keys
     ```
   - **Password**: your Mac account's password, stored encrypted on the phone.
3. Tap **Connect** and compare the host key fingerprint with the Mac's
   (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`). The app pins it and
   refuses a changed key unless you accept it again.

The app reconnects on its own when the network comes back or you return to it.

## Build

Needs JDK 17 and the Android SDK (API 35).

```sh
cd android
./gradlew :protocol:test        # the core client, on the JVM
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

- `protocol/`: plain Kotlin. The framed herdr-core connection (JSON-RPC on
  channel 0, terminal streams with credits), the event stream applied to its
  snapshot, and the layout document's tree operations, ported from the desktop
  app's `src/hc.rs`, `src/layout.rs` and `src/mirror.rs` and tested against a
  fake core.
- `app/`: the Android app (Jetpack Compose). `ssh/` connects and keeps keys,
  `Session.kt` is the state and every change, `term/` hosts xterm.js
  (`assets/term/`, MIT, see `XTERM-LICENSE`), `ui/` draws it.

The CI APK is signed with a debug key. Set up your own signing before
distributing it.
