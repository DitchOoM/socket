#!/usr/bin/env bash
# release-notes.py renders from a fixture: sections by type, a PR's `Release note` section under its
# title (and nothing else from the body), breaking changes first, dependabot apart.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../release-notes.py"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat >"$tmp/prs.json" <<'EOF'
[
  {"number": 5, "title": "build(deps): bump kotlin from 2.4.20 to 2.4.21", "body": "Bumps kotlin.", "author": "app/dependabot"},
  {"number": 4, "title": "test(http3): a drain test no longer races", "body": "Internal.", "author": "someone"},
  {"number": 3, "title": "feat(quic)!: streams wait at the limit", "body": "Why.\n\n## Release note\n\n`openStream()` now waits.\n\n```kotlin\n## not a heading inside a fence\nval s = conn.openStream()\n```\n\n## Tests\n\nNOT IN THE NOTES", "author": "someone"},
  {"number": 2, "title": "fix(quic): a lost packet is re-sent", "body": "### release notes:\nRecovers in one timeout.\n### Tests\nNOT IN THE NOTES", "author": "someone"},
  {"number": 6, "title": "fix(linux test): a hand-off test no longer times out", "body": "", "author": "someone"},
  {"number": 1, "title": "Plain title without a type", "body": "", "author": "someone"}
]
EOF

out="$(python3 -I "$script" --version 4.27.0 --previous-tag v4.26.0 --prs-json "$tmp/prs.json")"

fail() { echo "FAIL: $1"; echo "----- output -----"; echo "$out"; exit 1; }
has() { grep -qF -- "$1" <<<"$out" || fail "missing: $1"; }
hasnt() { ! grep -qF -- "$1" <<<"$out" || fail "unexpected: $1"; }
before() { [ "$(grep -nF -- "$1" <<<"$out" | head -1 | cut -d: -f1)" -lt "$(grep -nF -- "$2" <<<"$out" | head -1 | cut -d: -f1)" ] || fail "'$1' should come before '$2'"; }

has "**Maven Central:** [com.ditchoom:socket:4.27.0](https://central.sonatype.com/artifact/com.ditchoom/socket/4.27.0)"
has "## ⚠️ Breaking changes"
has "- Streams wait at the limit (#3)"
has "## New"
has "- **Streams wait at the limit** (#3)"
has "  \`openStream()\` now waits."
has "  ## not a heading inside a fence"
has "  val s = conn.openStream()"
has "## Fixes"
has "- **A lost packet is re-sent** (#2)"
has "  Recovers in one timeout."
has "## Dependencies"
has "- **Bump kotlin from 2.4.20 to 2.4.21** (#5)"
has "## Under the hood"
has "- **A drain test no longer races** (#4)"
has "- **Plain title without a type** (#1)"
before "## Under the hood" "- **A hand-off test no longer times out** (#6)"
has "**Full diff:** https://github.com/DitchOoM/socket/compare/v4.26.0...v4.27.0"
hasnt "NOT IN THE NOTES"
hasnt "Bumps kotlin."
hasnt "Internal."
before "## ⚠️ Breaking changes" "## New"
before "## New" "## Fixes"
before "## Fixes" "## Dependencies"
before "## Dependencies" "## Under the hood"

echo "release-notes: ok"
