#!/bin/sh
# Stamp one release version everywhere: the app's VERSION, each driver's
# DRIVER_VERSION and packageManifest.json (version, date, release notes).
# HPM updates only when the manifest version changes, so run this for every release.
#   scripts/release.sh 0.4.1 "What changed, in one line"
set -eu
cd "$(dirname "$0")/.."
version=${1:?usage: scripts/release.sh X.Y.Z "release notes"}
notes=${2:?usage: scripts/release.sh X.Y.Z "release notes"}
echo "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "version must look like 1.2.3" >&2; exit 1; }

sed -i '' -E "s/(static final String (DRIVER_)?VERSION = )\"[^\"]+\"/\1\"$version\"/" apps/*.groovy drivers/*.groovy
python3 - "$version" "$notes" <<'PY'
import json, sys, datetime
p = "packageManifest.json"
m = json.load(open(p))
m["version"], m["releaseNotes"] = sys.argv[1], sys.argv[2]
m["dateReleased"] = datetime.date.today().isoformat()
open(p, "w").write(json.dumps(m, indent=2) + "\n")
PY
grep -n 'VERSION = "' apps/*.groovy drivers/*.groovy
echo "Now run tests/run.sh, then commit."
