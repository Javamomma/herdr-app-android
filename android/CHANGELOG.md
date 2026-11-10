# Changelog

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
