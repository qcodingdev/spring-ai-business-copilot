#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JDK_DIR="$ROOT_DIR/.jdk"

case "$(uname -s)" in
  Darwin) OS="mac" ;;
  *)
    echo "Unsupported OS. This helper currently supports macOS only." >&2
    exit 1
    ;;
esac

case "$(uname -m)" in
  x86_64) ARCH="x64" ;;
  arm64|aarch64) ARCH="aarch64" ;;
  *)
    echo "Unsupported CPU architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

mkdir -p "$JDK_DIR"

ARCHIVE="$JDK_DIR/jdk-21-${OS}-${ARCH}.tar.gz"
URL="${JDK21_URL:-https://api.adoptium.net/v3/binary/latest/21/ga/${OS}/${ARCH}/jdk/hotspot/normal/eclipse?project=jdk}"

if [ ! -s "$ARCHIVE" ]; then
  echo "Downloading Eclipse Temurin JDK 21 for ${OS}/${ARCH}..."
  curl -L -o "$ARCHIVE" "$URL"
fi

echo "Extracting JDK into $JDK_DIR..."
tar -xzf "$ARCHIVE" -C "$JDK_DIR"

JAVA_HOME_CANDIDATE=$(find "$JDK_DIR" -path "*/Contents/Home/bin/java" -type f | sort | tail -n 1 | sed 's#/bin/java##')

if [ -z "$JAVA_HOME_CANDIDATE" ]; then
  echo "JDK extraction finished, but no macOS JAVA_HOME was found under $JDK_DIR." >&2
  exit 1
fi

"$JAVA_HOME_CANDIDATE/bin/java" -version
echo "Project-local JDK is ready: $JAVA_HOME_CANDIDATE"
