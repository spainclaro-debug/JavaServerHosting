#!/bin/sh
# Verify JavaHost JAR detection + the sample non-Minecraft server.
#
# Usage:
#   tools/verify-detection.sh                 # build sample jar, run hermetic tests
#   JAVAHOST_TESTTARGETS=/path/to/testtargets tools/verify-detection.sh
#                                             # also assert against real JARs
#
# `JAVAHOST_TESTTARGETS` is optional and may contain vanilla.jar, wiremock.jar
# and h2.jar; the real-JAR test skips itself when the variable is unset.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$HERE/.." && pwd)

if [ -z "${GRADLE_BIN:-}" ]; then
    for candidate in \
        "/data/data/com.termux/files/home/Deepseek projects/work/tools/gradle-8.9/bin/gradle" \
        "$PROJECT_ROOT/gradlew"
    do
        if [ -x "$candidate" ]; then
            GRADLE_BIN="$candidate"
            break
        fi
    done
fi
if [ -z "${GRADLE_BIN:-}" ]; then
    echo "error: Gradle not found. Set GRADLE_BIN=/path/to/gradle." >&2
    exit 1
fi

echo "==> Building sample generic server JAR"
"$HERE/sample-generic-server/build.sh"

echo "==> Booting sample generic server and probing HTTP"
PORT=18081
LOG=$(mktemp "${TMPDIR:-/tmp}/javahost-sample-server.XXXXXX") || LOG="$HERE/sample-generic-server/.verify-server.log"
JAVA_BIN=${JAVA:-java}
"$JAVA_BIN" -jar "$HERE/sample-generic-server/generic-server.jar" --port "$PORT" >"$LOG" 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true; rm -f "$LOG"' EXIT INT TERM

i=0
while [ "$i" -lt 30 ]; do
    if grep -q "Listening on port $PORT" "$LOG" 2>/dev/null; then
        break
    fi
    i=$((i + 1))
    sleep 1
done
if ! grep -q "Listening on port $PORT" "$LOG"; then
    echo "error: sample server did not print \"Listening on port $PORT\"; log:" >&2
    cat "$LOG" >&2 || true
    exit 1
fi
HEALTH=$(curl -sS -m 5 "http://127.0.0.1:$PORT/health" || true)
[ "$HEALTH" = "ok" ] || { echo "error: /health returned '$HEALTH' (expected 'ok')" >&2; exit 1; }
echo "    sample server OK: $HEALTH"
curl -sS -m 5 "http://127.0.0.1:$PORT/shutdown" >/dev/null 2>&1 || true
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=

echo "==> Running hermetic JAR + Modrinth unit tests"
cd "$PROJECT_ROOT"
if [ -n "${JAVAHOST_TESTTARGETS:-}" ]; then
    export JAVAHOST_TESTTARGETS
    echo "    real-JAR fixtures: $JAVAHOST_TESTTARGETS"
fi
"$GRADLE_BIN" :app:testDebugUnitTest \
    --tests 'dev.javahost.core.jar.*' \
    --tests 'dev.javahost.core.modrinth.*' \
    --console=plain

echo "==> All detection checks passed."
