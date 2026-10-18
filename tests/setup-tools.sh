#!/bin/sh
# Download a private JDK and Groovy into .tools/ (git-ignored) for tests/run.sh.
# Nothing is installed system-wide. Groovy 2.4 is the line Hubitat runs.
# Skip this if java and groovy are already on your PATH.
set -eu

cd "$(dirname "$0")/.."
TOOLS="$PWD/.tools"

GROOVY_VER=2.4.21
GROOVY_URL="https://archive.apache.org/dist/groovy/$GROOVY_VER/distribution/apache-groovy-binary-$GROOVY_VER.zip"
GROOVY_SHA=2f0f59aeaa054a241705b102111a2d823c19ee0f840a6c5bb3e2d26907058f40

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64)
        JDK_URL="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.32.1%2B1/OpenJDK11U-jdk_aarch64_mac_hotspot_11.0.32.1_1.tar.gz"
        JDK_SHA=c487a1c3a56588b3a32d0d07cdd526bf3d401c4b8422be772ca178bfcd647f3f ;;
    *)
        echo "No pinned JDK for $(uname -s)-$(uname -m); install a JDK 8-11 and Groovy 2.4 yourself." >&2
        exit 1 ;;
esac

fetch() { # url sha256 file
    curl -fsSL --retry 2 -o "$3" "$1"
    echo "$2  $3" | shasum -a 256 -c -
}

mkdir -p "$TOOLS"
DL="$(mktemp -d)"
trap 'rm -rf "$DL"' EXIT

if [ ! -x "$TOOLS/jdk/bin/java" ]; then
    fetch "$JDK_URL" "$JDK_SHA" "$DL/jdk.tar.gz"
    mkdir -p "$DL/jdk" && tar -xzf "$DL/jdk.tar.gz" -C "$DL/jdk"
    home="$(cd "$(dirname "$(find "$DL/jdk" -path '*/bin/java' -type f | head -1)")/.." && pwd)"
    rm -rf "$TOOLS/jdk" && mv "$home" "$TOOLS/jdk"
fi

if [ ! -x "$TOOLS/groovy/bin/groovy" ]; then
    fetch "$GROOVY_URL" "$GROOVY_SHA" "$DL/groovy.zip"
    unzip -q "$DL/groovy.zip" -d "$DL/groovy"
    rm -rf "$TOOLS/groovy" && mv "$DL/groovy/groovy-$GROOVY_VER" "$TOOLS/groovy"
fi

"$TOOLS/jdk/bin/java" -version
JAVA_HOME="$TOOLS/jdk" "$TOOLS/groovy/bin/groovy" --version
