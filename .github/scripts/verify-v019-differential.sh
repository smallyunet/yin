#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git -C "$root" archive v0.19.0 | tar -x -C "$work"
maven_repo="${MAVEN_REPO_LOCAL:-$work/m2}"
mvn --batch-mode -q -f "$work/pom.xml" -Dmaven.repo.local="$maven_repo" test
mvn --batch-mode -q -f "$work/pom.xml" -Dmaven.repo.local="$maven_repo" -DskipTests package

old=(java -jar "$work/target/yin-0.19.0.jar")
new=("$root/target/release/yin")
javac -cp "$work/target/yin-0.19.0.jar" -d "$work" "$root/conformance/V019Typecheck.java"
old_typecheck=(java -cp "$work:$work/target/yin-0.19.0.jar" V019Typecheck)

compare_output() {
  local program="$1"
  local old_output new_output
  old_output="$(cd "$root" && "${old[@]}" "$program")"
  new_output="$(cd "$root" && "${new[@]}" "$program")"
  if [[ "$old_output" != "$new_output" ]]; then
    printf 'differential mismatch: %s\nold: %s\nnew: %s\n' "$program" "$old_output" "$new_output" >&2
    return 1
  fi
}

for program in "$root"/tests/*.yin "$root"/examples/algorithms/*.yin \
  "$root"/examples/modules/main.yin "$root"/conformance/v019-positive/*.yin; do
  compare_output "${program#"$root/"}"
done

for program in "$root"/conformance/v019-reject/*.yin; do
  relative="${program#"$root/"}"
  if (cd "$root" && "${old_typecheck[@]}" "$relative" >/dev/null 2>&1); then
    printf 'v0.19 unexpectedly accepted: %s\n' "$relative" >&2
    exit 1
  fi
  if (cd "$root" && "${new[@]}" "$relative" >/dev/null 2>&1); then
    printf 'Rust unexpectedly accepted: %s\n' "$relative" >&2
    exit 1
  fi
done

printf 'v0.19 differential suite: positive outputs and rejection classifications match\n'
