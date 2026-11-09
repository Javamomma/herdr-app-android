# Sharing Herdr for Android

## Order

1. **Ask the Herdr maintainer first.** Can (`ogulcancelik` on GitHub) runs
   Herdr. Post in the Herdr Discord (https://discord.gg/PsgRfzmUDg) or open a
   short issue on `herdrdev/herdr-app`: you built an unofficial Android
   client that speaks herdr-core over SSH; is it OK to share under the name
   "Herdr for Android (unofficial)", and would they link it? Wait for the answer
   before the wider posts; they may prefer another name.
2. **Make the first release** (RELEASING.md) so there's one APK link to give.
3. **Post the demo in the Herdr Discord** (draft below), with the screenshots
   and a 20–30 s screen recording.
4. **Then wider**: r/GalaxyFold, r/androidapps, the Claude Code and coding
   agent communities, Hacker News "Show HN" once a few people have tried it.
5. **Later**: F-Droid (free, builds from source, review takes weeks), Google
   Play ($25 account, review, data-safety form, closed testing first).

## The screen recording

On the Fold: swipe down twice → Screen recorder (Media and sounds). Record:

1. Unfolded: the sidebar with spaces and agents, tap into a split.
2. Fold it: the cover screen opens on the agents list.
3. A blocked agent's notification → **Allow** from the notification.
4. Unfold: back in the same tab, nothing restarted.

## Discord post (draft)

> **Herdr for Android: your agents in your pocket (unofficial)**
>
> I wanted my Herdr spaces and agents on my Galaxy Fold while travelling, so I
> built an Android client. It SSHes to my Mac mini, runs `hc connect`, and
> speaks herdr-core's protocol, so the phone shows the same spaces, tabs,
> splits and agents as the desktop app, live both ways.
>
> • Notifications when an agent is blocked, with Allow / Deny / Reply
> • Unfolded: sidebar + splits; folded: the cover screen opens on your agents
> • Native terminal (truecolor, mouse, scrollback, copy) + a Type box for
>   autocorrect and voice
> • Tailscale-friendly, reconnects when you switch networks; terminals keep
>   running on the Mac
> • Saved commands, widget, app lock. No accounts or analytics.
>
> Try it without a Mac: install, tap **Try the demo**.
> APK + source: https://github.com/Javamomma/Herdr-Android (Apache-2.0)
>
> Not official, Herdr is Can's work and this just talks to herdr-core.
> Feedback welcome, especially from other foldable and Android users!
