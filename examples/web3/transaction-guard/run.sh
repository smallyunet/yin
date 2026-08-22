#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)
YIN_BIN="${YIN_BIN:-$REPOSITORY_ROOT/target/release/yin}"

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <transaction-intent.json>" >&2
  exit 2
fi

if [ ! -x "$YIN_BIN" ]; then
  echo "missing $YIN_BIN; run cargo build --release --workspace first" >&2
  exit 2
fi

exec "$YIN_BIN" --json "$SCRIPT_DIR/main.yin" < "$1"
