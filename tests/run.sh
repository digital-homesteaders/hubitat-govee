#!/bin/sh
# Compile-check the app and drivers and run the mapping tests, without a hub.
# Uses java/groovy from .tools/ (see tests/setup-tools.sh) or else from PATH.
set -eu

cd "$(dirname "$0")/.."
if [ -x .tools/groovy/bin/groovy ]; then
    export JAVA_HOME="$PWD/.tools/jdk"
    GROOVY=.tools/groovy/bin/groovy
    GROOVYC=.tools/groovy/bin/groovyc
else
    GROOVY=groovy
    GROOVYC=groovyc
fi

BUILD=tests/build
rm -rf "$BUILD" && mkdir -p "$BUILD"
# Groovy 2.4 on Java 9+ warns about reflective access; that noise is filtered.
if ! "$GROOVYC" -d "$BUILD" tests/support/*.groovy > "$BUILD/out.txt" 2>&1; then
    grep -v '^WARNING: ' "$BUILD/out.txt" >&2
    exit 1
fi

status=0
for t in tests/CompileCheck.groovy tests/PackageCheck.groovy tests/MappingTest.groovy tests/DriverFlowTest.groovy; do
    [ -f "$t" ] || continue
    echo "== $t"
    "$GROOVY" -cp "$BUILD" "$t" > "$BUILD/out.txt" 2>&1 || status=1
    grep -v '^WARNING: ' "$BUILD/out.txt" || true
done
[ $status -eq 0 ] && echo "All passed." || echo "FAILED" >&2
exit $status
