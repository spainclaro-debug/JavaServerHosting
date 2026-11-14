#!/bin/sh
# Build tools/sample-generic-server/generic-server.jar with plain javac + jar.
# No Gradle, no dependencies: this is the standalone non-Minecraft test target.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$HERE"

JAVAC=${JAVAC:-javac}
JAR=${JAR:-jar}

if ! command -v "$JAVAC" >/dev/null 2>&1; then
    echo "error: javac not found. Install a JDK (17+) or set JAVAC=/path/to/javac." >&2
    exit 1
fi
if ! command -v "$JAR" >/dev/null 2>&1; then
    echo "error: jar not found. Install a JDK (17+) or set JAR=/path/to/jar." >&2
    exit 1
fi

SRC_DIR="$HERE/src"
OUT_DIR="$HERE/build/classes"
JAR_FILE="$HERE/generic-server.jar"

if ! find "$SRC_DIR" -name '*.java' -print | grep -q .; then
    echo "error: no Java sources found under $SRC_DIR" >&2
    exit 1
fi

rm -rf "$HERE/build"
mkdir -p "$OUT_DIR"

echo "[build] compiling with $("$JAVAC" -version 2>&1)"
# --release 17: runs on JavaHost's minimum bundled JRE (17).
find "$SRC_DIR" -name '*.java' -exec "$JAVAC" --release 17 -encoding UTF-8 \
    -d "$OUT_DIR" {} +

rm -f "$JAR_FILE"
"$JAR" --create --file "$JAR_FILE" \
    --main-class dev.javahost.samples.generic.GenericServer \
    -C "$OUT_DIR" .

# Prove the manifest entry point is present.
if command -v unzip >/dev/null 2>&1; then
    if ! unzip -p "$JAR_FILE" META-INF/MANIFEST.MF | grep -q "Main-Class: dev.javahost.samples.generic.GenericServer"; then
        echo "error: built jar is missing the Main-Class manifest entry" >&2
        exit 1
    fi
fi

rm -rf "$HERE/build"
echo "[build] wrote $JAR_FILE ($(wc -c < "$JAR_FILE") bytes)"
echo "[build] smoke test:"
JAVA=${JAVA:-java}
if command -v "$JAVA" >/dev/null 2>&1; then
    "$JAVA" -jar "$JAR_FILE" --help || true
fi
