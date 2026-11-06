#!/usr/bin/env bash
# Local, read-only: prints "<git-blob-sha> <sha256> <size> <path>" for every file in hosting/hostinger/api.
# Save the output before a deploy and diff it against what you uploaded, so repo and live files can be compared by hash instead of by size.
#   tools/deploy_manifest.sh > manifest.txt
#   tools/deploy_manifest.sh --check manifest.txt     # exit 1 if any file differs from the saved manifest
set -euo pipefail
cd "$(dirname "$0")/../hosting/hostinger/api"
gen() { find . -type f ! -name '*.sqlite' ! -name 'relay.json' | LC_ALL=C sort | while read -r f; do printf '%s %s %s %s\n' "$(git hash-object "$f")" "$(sha256sum "$f" | cut -d' ' -f1)" "$(wc -c < "$f" | tr -d ' ')" "${f#./}"; done; }
if [ "${1:-}" = "--check" ]; then diff <(gen) "$2" && echo "manifest matches" ; else gen; fi
