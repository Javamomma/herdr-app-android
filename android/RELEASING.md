# Releasing

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
