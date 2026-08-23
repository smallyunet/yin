#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
map="$root/conformance/v019-test-classes.tsv"

test -f "$map"
legacy_total=0
mapped_total=0
class_total=0

while IFS=$'\t' read -r class expected evidence; do
  [[ -z "$class" || "$class" == \#* ]] && continue
  class_total=$((class_total + 1))
  test -e "$root/$evidence"
  file="$(git -C "$root" ls-tree -r --name-only v0.19.0 src/test/java | grep "/${class}\\.java$")"
  test -n "$file"
  actual="$(git -C "$root" show "v0.19.0:$file" | grep -c '@Test')"
  test "$actual" = "$expected"
  legacy_total=$((legacy_total + actual))
  mapped_total=$((mapped_total + expected))
done < "$map"

test "$class_total" = 27
test "$legacy_total" = 234
test "$mapped_total" = 234
printf 'v0.19 migration map: %s classes, %s tests accounted for\n' "$class_total" "$mapped_total"
