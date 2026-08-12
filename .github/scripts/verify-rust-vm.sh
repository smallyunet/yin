#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: verify-rust-vm.sh <version>}"
jar="target/yin-${version}.jar"
yinvm="vm/target/release/yinvm"
temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

program="examples/agents/capability-decision/main.yin"
inputs="examples/agents/capability-decision/inputs"
bytecode="${temp_dir}/capability.ybc"

java -jar "${jar}" --contract-compile "${program}" --output "${bytecode}"
"${yinvm}" check "${bytecode}"

for fixture in approve.json needs-approval.json reject.json; do
  source_result="$(java -jar "${jar}" --contract-run "${program}" --input "${inputs}/${fixture}")"
  vm_result="$("${yinvm}" run "${bytecode}" --input "${inputs}/${fixture}" --fuel 100000)"
  node -e '
    const source = JSON.parse(process.argv[1]);
    const vm = JSON.parse(process.argv[2]);
    if (JSON.stringify(source.result) !== JSON.stringify(vm.result)) process.exit(1);
    if (source.resultHash !== vm.resultHash) process.exit(1);
  ' "${source_result}" "${vm_result}"
done

if "${yinvm}" run "${bytecode}" --input "${inputs}/approve.json" --fuel 1; then
  echo "expected low-fuel VM execution to fail" >&2
  exit 1
fi
