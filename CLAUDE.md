# Working rules for this repo

## Release deliverables (always, for every change or update)
- **Android:** produce BOTH a signed `.apk` (testing / sideloading) and a signed `.aab` (Google Play upload).
  Build with `android/release.sh`. It runs the unit tests, then writes `dist/<versionName>/RoadAhead-<v>.apk`, `.aab` and `SHA256SUMS`.
- **Web version** (`public/` + `server/`): update it too when a change applies to it. Do NOT create a web or desktop
  version for an Android-only tool unless the owner asks.

## Version control
- Bump `android/version.properties` for every release. `versionCode` must always increase; `versionName` is semver.
- Never overwrite or delete an earlier build. Each version lives in its own `dist/<version>/` folder and stays committed as a backup.
  `release.sh` refuses to write into an existing version folder.
- Record each release in `dist/CHANGELOG.md`.

## Signing
- Releases are signed with the owner's personal/upload key: `android/keystore/roadahead-personal.jks`, with details in
  `android/keystore.properties`. Both are git-ignored and must never be committed.
  If they're missing, ask the owner for the `.jks` file rather than generating a new key: a new key breaks in-place updates and Play uploads.
- Google Play requires the target API level it currently demands (API 36 as of Aug 2026). Check this before each Play release.
