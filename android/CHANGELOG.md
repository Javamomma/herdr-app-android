# Changelog

## [Unreleased]

### New
- Sign-in is by this phone's key only: the Password option is gone, a password saved by an older version is deleted, and a saved password profile now uses the key.
- Release builds are signed with a private key and refuse to build without it (the app is not yet released this way).
- The left panel can be hidden at every width with one tap, and Back hides it. Folded, it opens with the button (swiping from the edge is off, so sideways drags stay with the terminal).
- Paste sits beside Esc and goes to the box you are typing in; it says "Nothing to paste" when the clipboard has no text.
- Unnamed tabs show their agent's title, else their number.
- Terminal selection bar: Select all (everything, scrollback included; blank lines at the bottom are left out) and Paste, beside Copy and Clear. After a long-press, drag either end of the selection to adjust it.
- A very large copy is cut to its newest 200,000 characters, and a notice says so, instead of failing on Android's clipboard limit.

### Fixed
- Multi-line text from the keyboard is sent as one paste instead of line by line.
- Long-pressing older text in the terminal no longer closes the app.
- Copy with nothing selected leaves the clipboard alone.
- Send keeps your draft unless the Mac confirmed it, and says what happened: not sent, or maybe sent.
- Answer buttons only answer the question that was shown.

## [0.2.0] - 2026-11-09

### New
- Notifications when an agent needs you or finishes. A blocked agent's notification shows its question, with Allow, Deny and Reply.
- Answers from a notification need the phone unlocked, and are sent only while the agent still shows the question you saw. If an answer can't be sent, a notification tells you.
- A command that can't be taken back (a force push, `rm -rf`) is never a one-tap Allow: answer it in the app.
- App lock with fingerprint, face or PIN. With it on, notifications and the widget name no agent, notifications have no buttons, and the recent-apps view shows no picture of your terminals.
- Closing a pane or tab asks first when an agent or program is running in it.
- Folded, the cover screen opens on your agents; a home-screen widget shows blocked, done and working.
- Saved commands (⚡ on the key bar): type one into the terminal or start it in a new tab.
- Try the demo from the connect screen, no Mac needed.
- Reconnects at once when the phone switches networks.
- A keyboard key (⌨) on the key bar; ^C sits next to Esc.
- Answer an agent's question with its own options, right from the agents list; risky answers need a press and hold.
- Agent cards show the last lines of each agent's screen.
- Opens instantly on what was there last time while it reconnects.
- Tap a web address in a terminal to open it, or a file path to view it.
- Quick phrases and slash commands above the Type box.
- Tells you in Settings when a new version is out.
- New space can start with TUIOS when it's installed on that machine; it reattaches to your last TUIOS session.

### Fixed
- The "Watching agents" notification could stay up with nothing connected after Android restarted the app in the background. It now reconnects, and says so when it isn't connected.
- Disconnecting while a connection was still being made could connect anyway.
- The keyboard came back only after switching tabs when you folded or unfolded the phone.

## [0.1.0] - 2026-11-07

### New
- First version: spaces, tabs, splits and agents shared with the Herdr desktop app, over SSH to herdr-core.
