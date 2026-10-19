# Releasing iSMS GW

Releases are fully automated with GitHub Actions: pushing a tag `vX.Y.Z` builds a **signed APK and AAB**
and publishes them as a **GitHub Release** with notes taken from `CHANGELOG.md`.

## One-time setup: signing secrets

The upload key must never be committed. Store it in the repository secrets
(**Settings → Secrets and variables → Actions → New repository secret**):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The keystore file encoded as base64 (see below) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Encode the keystore:

Run from the project root (replace `upload.jks` with your keystore file name):

```bash
# Linux / macOS
base64 -w0 keystore/upload.jks > keystore.b64
```

```powershell
# Windows PowerShell: copies the base64 text straight to the clipboard.
# Resolve-Path is required: .NET resolves relative paths against the process folder, not the current PowerShell location.
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path ".\keystore\upload.jks"))) | Set-Clipboard
```

Paste the result into `KEYSTORE_BASE64` (and delete `keystore.b64` if you created it).
Keep a secure offline backup of the keystore and its passwords — Google Play updates are impossible without it
(unless you use Play App Signing and request an upload-key reset).

If the secrets are missing, the workflow still runs but produces **unsigned** artifacts.

## Cutting a release

1. Make sure everything for the release is merged into `main` and listed under `## [Unreleased]` in `CHANGELOG.md`.
2. Run the release script with the new [SemVer](https://semver.org/) version:

   ```bash
   ./scripts/release.sh 1.2.0
   ```

   It updates `version.properties`, moves the *Unreleased* notes under `## [1.2.0] - <date>`,
   commits `Release v1.2.0` and creates the annotated tag `v1.2.0`.
3. Push the commit and the tag:

   ```bash
   git push origin main --tags
   ```

4. Watch **Actions → Release**. When it finishes, the release appears under **Releases** with:
   - `iSMS-GW-1.2.0-release.apk` — for direct installation,
   - `iSMS-GW-1.2.0-release.aab` — for Google Play,
   - `SHA256SUMS.txt` — checksums.

The workflow refuses to publish when the tag does not match `version.properties`.
To rebuild an existing tag use **Actions → Release → Run workflow** and enter the tag.

## Versioning rules

- **PATCH** (`1.2.1`) — bug fixes only.
- **MINOR** (`1.3.0`) — new features, backwards compatible (API v1 stays compatible).
- **MAJOR** (`2.0.0`) — breaking changes to the REST API, webhooks or the persistent-link protocol.

`versionCode` is derived automatically: `MAJOR*10000 + MINOR*100 + PATCH`.

## Google Play

Upload the `.aab` from the release to the Play Console. See [google-play.md](google-play.md)
for the store listing, permission declarations and Data safety answers.
