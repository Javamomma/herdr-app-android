# Privacy

Herdr for Android connects only to the computer you enter (your Mac), over
SSH. It has no accounts, analytics, ads or crash reporting, and no server of
its own.

**Kept on the phone**
- The Mac's address, user name and port.
- The phone's own SSH key and, if you chose it, your Mac password: encrypted
  with a key held by the Android Keystore, never sent anywhere but the SSH
  login.
- The Mac's host key fingerprint, your saved commands, view and settings.

**Shown, not stored**
Terminal screens, files and git output come from your Mac as you look at them.

**Background connection**
With notifications on, the app keeps its SSH connection open in the
background so it can tell you when an agent needs you. Turn this off in
Settings.

**Notifications**
They show an agent's name, status and the title it reports, on your phone
only. Allow, Deny and Reply send keys or text to that agent on your Mac.

Uninstalling removes everything the app stored.
