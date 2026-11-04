#!/usr/bin/env bash
# Build a release: signed APK (for testing/sideloading) + signed AAB (for Google Play).
# Outputs go to ../dist/<versionName>/ and an existing version folder is NEVER overwritten —
# bump versionCode/versionName in version.properties first.
set -euo pipefail
cd "$(dirname "$0")"
VN=$(grep '^versionName=' version.properties | cut -d= -f2)
VC=$(grep '^versionCode=' version.properties | cut -d= -f2)
OUT="../dist/$VN"
if [ -e "$OUT" ]; then echo "dist/$VN already exists — bump version.properties (old builds are kept as backups)." >&2; exit 1; fi
[ -f keystore.properties ] || { echo "keystore.properties missing — the release must be signed with the personal/upload key." >&2; exit 1; }
GRADLE=${GRADLE:-$( [ -x ./gradlew ] && echo ./gradlew || echo gradle )}
$GRADLE --console=plain -q :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
mkdir -p "$OUT"
cp app/build/outputs/apk/release/app-release.apk "$OUT/RoadAhead-$VN.apk"
cp app/build/outputs/bundle/release/app-release.aab "$OUT/RoadAhead-$VN.aab"
( cd "$OUT" && sha256sum RoadAhead-$VN.apk RoadAhead-$VN.aab > SHA256SUMS )
echo "Built $VN (versionCode $VC) → dist/$VN/"
