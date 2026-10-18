#!/bin/sh
# Point the HPM manifest, the import URLs and the README at the GitHub account that
# hosts this repository (replaces the GITHUB_OWNER placeholder).
#   scripts/set-github-owner.sh my-github-name
set -eu
cd "$(dirname "$0")/.."
owner=${1:?usage: scripts/set-github-owner.sh OWNER}
echo "$owner" | grep -Eq '^[A-Za-z0-9-]+$' || { echo "not a GitHub name: $owner" >&2; exit 1; }
grep -l GITHUB_OWNER packageManifest.json README.md apps/*.groovy drivers/*.groovy | while read -r f; do
    sed -i '' "s/GITHUB_OWNER/$owner/g" "$f"
    echo "updated $f"
done
