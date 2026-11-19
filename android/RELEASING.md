# Releasing

**Releases are paused: there is no public release and no release workflow in this repository for now.**

## How CI signs

The `android` workflow keeps its debug build (artifact `herdr-android-apk`, signed with the
public `debug.keystore`; use it for tests only). When the repository secrets are available
(pushes and same-repo pull requests, not forks) it also builds `:app:assembleRelease`
with the private key and uploads `herdr-android-release-apk`. The keystore is decoded
into `$RUNNER_TEMP`, passed to Gradle by environment variable only, and deleted at the
end. A release build without `HERDR_KEYSTORE` and `HERDR_KEYSTORE_PASSWORD` fails on
purpose.

Release certificate SHA-256:
`e77aa4e4df4f58d5b98ee736bbe7a1318c1b1381e742ecedca616a7e0166cbed`

Check an APK (CI does this and fails on a mismatch):

```sh
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs herdr-release.apk
```

Every "certificate SHA-256 digest:" line (the prefix differs between build-tools
versions, e.g. "Signer #1 …" or "V2 Signer: …") must equal the fingerprint above;
CI fails if there is none or any differs.

## One-time switch from the debug-signed app

The release APK cannot update a debug-signed install (different key), so once:

1. Uninstall the debug-signed Herdr from the phone.
2. Install the `herdr-android-release-apk` build.
3. Open it and copy the phone's new public key (connect screen, "Copy key").
4. On the Mac add it to `~/.ssh/authorized_keys` and delete the old line (the previous
   `herdr-android` key), so the old install's key no longer logs in.

Releases are APKs on GitHub Releases, signed with one project key so each
installs over the last.

## Once: make the signing key and add it as secrets

On your Mac (no Java needed):

```sh
openssl req -x509 -newkey rsa:4096 -sha256 -days 10000 -nodes \
  -keyout herdr-key.pem -out herdr-cert.pem -subj "/CN=Herdr for Android"
openssl pkcs12 -export -inkey herdr-key.pem -in herdr-cert.pem -name herdr \
  -out herdr-release.p12
base64 -i herdr-release.p12 | pbcopy      # now on the clipboard
```

`pkcs12 -export` asks for a password: that's the keystore password.

In the repository on GitHub: Settings → Secrets and variables → Actions →
New repository secret, three times:

| Name | Value |
|---|---|
| `HERDR_KEYSTORE_B64` | paste (the base64 from above) |
| `HERDR_KEYSTORE_PASSWORD` | the password you chose |
| `HERDR_KEY_ALIAS` | `herdr` |

Then keep `herdr-release.p12` and its password somewhere safe (a password
manager) and delete the `.pem` files. Losing the key means users must
uninstall to update.

## Each release

1. Add a `## [x.y.z] - YYYY-MM-DD` section to CHANGELOG.md and bump
   `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit, then tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. The `release` workflow tests, builds, signs and publishes the release
   with that CHANGELOG section as its notes.
