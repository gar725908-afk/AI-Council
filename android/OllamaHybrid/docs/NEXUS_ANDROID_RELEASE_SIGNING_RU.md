# NEXUS Android release signing

The release workflow is intentionally blocked until a stable upload key is configured. Never commit the keystore, its passwords, or a Base64 copy to Git.

## Required GitHub Actions repository secrets

Create these four secrets in Settings → Secrets and variables → Actions:

- `NEXUS_ANDROID_KEYSTORE_BASE64` — Base64 content of the upload keystore file.
- `NEXUS_ANDROID_KEYSTORE_PASSWORD` — keystore password.
- `NEXUS_ANDROID_KEY_ALIAS` — key alias.
- `NEXUS_ANDROID_KEY_PASSWORD` — key password.

Generate the upload key once on a trusted computer:

```bash
keytool -genkeypair -v -keystore nexus-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias nexus-upload
```

PowerShell: convert the binary keystore to a one-line Base64 value and copy it for the GitHub secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\\nexus-upload.jks")) | Set-Clipboard
```

Keep at least two offline backups of `nexus-upload.jks` and its passwords. The same key must sign every future APK that updates this installed package. Do not regenerate it for a later release.

## Publish a release

After the four secrets are saved, create and push a semantic-version tag from the NEXUS branch:

```bash
git tag nexus-v0.6.1
git push origin nexus-v0.6.1
```

The workflow runs unit tests, builds the signed APK and AAB, computes the APK SHA-256, and publishes a GitHub Release. NEXUS Update Center filters only `nexus-v*` tags and ignores desktop releases.

## First installation and updates

The current CI debug APK is signed with a CI debug certificate. A production release uses the long-lived upload key above. Android will not install the release over a debug-signed package. The first production switch therefore needs a clean install unless app data has first been exported through an explicit backup/migration feature. Subsequent production releases can update in place while the upload key remains unchanged.

The app downloads only an HTTPS GitHub release asset, checks its published SHA-256, package ID, version and signing certificate, then invokes Android's system installer. Android still requires the user to confirm installation; NEXUS does not silently install APKs.
