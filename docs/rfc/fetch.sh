#!/usr/bin/env bash
# Fetch or verify the vendored RFC texts. See README.md.
#
#   ./fetch.sh --verify     verify every vendored file against CHECKSUMS.txt
#   ./fetch.sh 9000 9002    (re-)fetch those RFCs and refresh their checksums
#   ./fetch.sh --all        re-fetch everything already vendored
set -euo pipefail
cd "$(dirname "$0")"

sha() { shasum -a 256 "$@"; }

if [ "${1:-}" = "--verify" ]; then
    # A published RFC is immutable, so any mismatch means the local copy was edited or truncated --
    # never that the document moved on.
    sha --check CHECKSUMS.txt
    exit $?
fi

targets=()
if [ "${1:-}" = "--all" ]; then
    for f in rfc*.txt; do targets+=("${f#rfc}"); done
    targets=("${targets[@]%.txt}")
elif [ $# -gt 0 ]; then
    targets=("$@")
else
    echo "usage: $0 [--verify | --all | <rfc-number>...]" >&2
    exit 2
fi

for n in "${targets[@]}"; do
    url="https://www.rfc-editor.org/rfc/rfc$n.txt"
    echo "fetching $url"
    curl -fsS --max-time 60 -o "rfc$n.txt.new" "$url"
    # Guard against a courtesy error page landing as an RFC: the real text always names itself.
    if ! grep -qiE "^(Internet Engineering Task Force|Network Working Group|Independent Submission)" "rfc$n.txt.new"; then
        rm -f "rfc$n.txt.new"
        echo "rfc$n: fetched content is not an RFC text -- refusing to vendor it" >&2
        exit 1
    fi
    mv "rfc$n.txt.new" "rfc$n.txt"
done

sha rfc*.txt > CHECKSUMS.txt
echo "CHECKSUMS.txt refreshed"
