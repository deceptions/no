#!/usr/bin/env bash
# Publishes the APKs in ./apks as a GitHub release the in-app updater understands.
# Usage: scripts/publish-release.sh <version> [stable|beta] [owner/repo]   (needs the GitHub CLI: gh auth login)
#   stable -> normal release: Nekomo-{phone,tv}-release.apk
#   beta   -> pre-release:    Nekomo-{phone,tv}-beta.apk  (+ debug apks)
# Asset names must stay Nekomo-<phone|tv>-<release|beta|debug>.apk; each app only updates to its own APK.
set -euo pipefail
VERSION="${1:?version, e.g. 2.8.2}"; CHANNEL="${2:-stable}"; REPO="${3:-Nekomo-App/Nekomo}"
cd "$(dirname "$0")/.."
if [ "$CHANNEL" = "beta" ]; then
  FILES=(apks/Nekomo-phone-beta.apk apks/Nekomo-TV-beta.apk apks/Nekomo-phone-alpha.apk apks/Nekomo-TV-alpha.apk); FLAGS=(--prerelease)
else
  FILES=(apks/Nekomo-phone-release.apk apks/Nekomo-TV-release.apk); FLAGS=()
fi
gh release create "v$VERSION" "${FILES[@]}" --repo "$REPO" --title "Nekomo $VERSION" --generate-notes "${FLAGS[@]}"
