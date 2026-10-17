#!/usr/bin/env bash
# Usage: ./scripts/release.sh 1.2.0
# Bumps version.properties, moves the [Unreleased] CHANGELOG section under the new version,
# commits and creates the tag vX.Y.Z. Push with: git push origin main --tags
set -euo pipefail

VERSION="${1:-}"
if ! [[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Usage: $0 MAJOR.MINOR.PATCH" >&2; exit 1
fi
MAJOR="${BASH_REMATCH[1]}"; MINOR="${BASH_REMATCH[2]}"; PATCH="${BASH_REMATCH[3]}"
cd "$(dirname "$0")/.."

[ -z "$(git status --porcelain)" ] || { echo "Working tree is not clean — commit or stash first." >&2; exit 1; }
git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && { echo "Tag v$VERSION already exists." >&2; exit 1; }
grep -q '^## \[Unreleased\]' CHANGELOG.md || { echo "CHANGELOG.md has no [Unreleased] section." >&2; exit 1; }

sed -i.bak -e "s/^VERSION_MAJOR=.*/VERSION_MAJOR=$MAJOR/" \
           -e "s/^VERSION_MINOR=.*/VERSION_MINOR=$MINOR/" \
           -e "s/^VERSION_PATCH=.*/VERSION_PATCH=$PATCH/" version.properties && rm -f version.properties.bak

TODAY="$(date +%Y-%m-%d)"
REPO="https://github.com/iSmartyPRO/isms-gw-android"
awk -v v="$VERSION" -v d="$TODAY" -v repo="$REPO" '
  /^## \[Unreleased\]/ { print; print ""; print "## [" v "] - " d; next }
  /^\[Unreleased\]:/   { print "[Unreleased]: " repo "/compare/v" v "...HEAD"; print "[" v "]: " repo "/releases/tag/v" v; next }
  { print }
' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md

git add version.properties CHANGELOG.md
git commit -m "Release v$VERSION"
git tag -a "v$VERSION" -m "iSMS GW $VERSION"
echo "Created tag v$VERSION. Publish with: git push origin main --tags"
