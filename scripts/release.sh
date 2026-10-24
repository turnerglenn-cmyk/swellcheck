#!/usr/bin/env bash
# Publish a release after building the signed APK in Android Studio
# (Build > Generate Signed App Bundle or APK > APK > release).
# Usage: scripts/release.sh
set -euo pipefail
cd "$(dirname "$0")/.."

APK=app/release/app-release.apk
NAME=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
TAG="v$NAME"
NOTES="fastlane/metadata/android/en-US/changelogs/$CODE.txt"

[ -f "$APK" ] || { echo "No $APK - build the signed release APK in Android Studio first."; exit 1; }
AAPT=$(ls -d ~/Android/Sdk/build-tools/*/ | sort -V | tail -1)aapt
BUILT=$("$AAPT" dump badging "$APK" | head -1)
echo "$BUILT"
echo "$BUILT" | grep -q "versionCode='$CODE'" || { echo "The APK isn't version $CODE ($NAME). Sync Gradle and rebuild."; exit 1; }
[ -f "$NOTES" ] || { echo "Missing $NOTES"; exit 1; }

git add -A
git commit -m "Release $NAME" || echo "(nothing new to commit)"
git push
if git rev-parse "$TAG" >/dev/null 2>&1; then echo "Tag $TAG already exists"; else git tag "$TAG" && git push origin "$TAG"; fi
if gh release view "$TAG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$APK" --clobber
else
  gh release create "$TAG" "$APK" --title "SwellCheck $NAME" --notes-file "$NOTES"
fi
echo "Done: https://github.com/turnerglenn-cmyk/swellcheck/releases/tag/$TAG"
