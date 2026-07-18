#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_HOME_CANDIDATE=""
JAVA_SOURCE=""

LOCAL_JAVA=$(find "$ROOT_DIR/.jdk" -path "*/bin/java" -type f 2>/dev/null | sort | tail -n 1)
if [ -n "$LOCAL_JAVA" ]; then
  JAVA_HOME_CANDIDATE=${LOCAL_JAVA%/bin/java}
  JAVA_SOURCE="project-local JDK"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_HOME_CANDIDATE=$JAVA_HOME
  JAVA_SOURCE="JAVA_HOME"
fi

if [ -z "$JAVA_HOME_CANDIDATE" ]; then
  echo "Java 21 was not found." >&2
  echo "Run ./scripts/install-jdk21.sh, place a JDK under .jdk/, or set JAVA_HOME to a JDK 21 installation." >&2
  exit 1
fi

JAVA_VERSION_OUTPUT=$("$JAVA_HOME_CANDIDATE/bin/java" -version 2>&1)
case "$JAVA_VERSION_OUTPUT" in
  *\"21.*) ;;
  *)
    echo "The selected $JAVA_SOURCE is not Java 21:" >&2
    echo "$JAVA_VERSION_OUTPUT" >&2
    exit 1
    ;;
esac

export JAVA_HOME="$JAVA_HOME_CANDIDATE"
export PATH="$JAVA_HOME/bin:$PATH"

exec mvn "$@"
