# Privacy

Herdr for Android connects to the computer you enter (your Mac), over SSH. It
has no accounts, analytics, ads or crash reporting, and no server of its own.

The one other place it contacts is GitHub, at most twice a day, to ask whether
a newer version of the app is out. That request carries nothing about you or
your Mac.

**Kept on the phone**
- The Mac's address, user name and port.
- The phone's own SSH key and, if you chose it, your Mac password: encrypted
  with a key held by the Android Keystore, never sent anywhere but the SSH
  login.
- The Mac's host key fingerprint, your saved commands, view and settings.
- The names and folders of your spaces, tabs and terminals as they were when
  you last connected, so the app opens at once. Not what is on their screens.

**Shown, not stored**
Terminal screens, files and git output come from your Mac as you look at them.

**Background connection**
With notifications on, the app keeps its SSH connection open in the
background so it can tell you when an agent needs you. Turn this off in
Settings.

**Notifications**
They show an agent's name, status, the title it reports and, for an agent
waiting on you, the question on its screen, on your phone only. On a locked
phone set to hide sensitive notifications, they say only that an agent needs
you. Allow, Deny and Reply send keys
or text to that agent on your Mac, need the phone unlocked, and are sent only
while the agent still shows the question you saw. With the app lock on,
notifications and the widget name no agent and have no buttons.

Uninstalling removes everything the app stored.
