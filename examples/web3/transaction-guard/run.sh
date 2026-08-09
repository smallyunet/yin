#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)
YIN_VERSION=0.11.0
YIN_JAR="$REPOSITORY_ROOT/target/yin-$YIN_VERSION.jar"

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <transaction-intent.json>" >&2
  exit 2
fi

if [ ! -f "$YIN_JAR" ]; then
  echo "missing $YIN_JAR; run ./mvnw package first" >&2
  exit 2
fi

exec java -jar "$YIN_JAR" --json "$SCRIPT_DIR/main.yin" < "$1"
