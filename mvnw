#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_HOME_CANDIDATE=$(find "$ROOT_DIR/.jdk" -path "*/Contents/Home/bin/java" -type f 2>/dev/null | sort | tail -n 1 | sed 's#/bin/java##')

if [ -z "$JAVA_HOME_CANDIDATE" ]; then
  echo "Project-local JDK 21 was not found." >&2
  echo "Run ./scripts/install-jdk21.sh first, or place a macOS JDK 21 under .jdk/." >&2
  exit 1
fi

JAVA_VERSION_OUTPUT=$("$JAVA_HOME_CANDIDATE/bin/java" -version 2>&1)
case "$JAVA_VERSION_OUTPUT" in
  *\"21.*) ;;
  *)
    echo "The project-local JDK is not Java 21:" >&2
    echo "$JAVA_VERSION_OUTPUT" >&2
    exit 1
    ;;
esac

export JAVA_HOME="$JAVA_HOME_CANDIDATE"
export PATH="$JAVA_HOME/bin:$PATH"

exec mvn "$@"
