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
The Queue (the agents list) shows what a command you set in Settings prints on your Mac
(by default your own `panel.py queue`): titles and details of things waiting on you. It is
read while the list is on screen with the app in front, kept only in memory, never saved on
the phone and never in a notification. Clear the command in Settings to turn it off.

**Photos you attach**
A photo you pick (or share to Herdr from another app) is read through Android's photo picker or the
sharing app's grant, so Herdr never gets access to your gallery or storage. It is turned upright, shrunk,
re-encoded without location or other metadata, and held in the app's cache only while it uploads; the
copy is deleted when the upload ends, fails or is cancelled. It is sent over your SSH connection to your Mac,
where herdr-core keeps it in a folder of its own beside that terminal until the terminal is removed. The file is
named by the time (`photo-20261119-135600.jpg`), never by its original name. The app does not keep the picture
or its path after you send the message.

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
