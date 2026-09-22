#!/usr/bin/env bash
#
# Tests for ci-version.sh against throwaway git repositories.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../ci-version.sh"
pass=0
fail=0

check() { # <name> <expected-exit> <actual-exit> <output> <expected-output-or-needle>
  local name="$1" want="$2" got="$3" out="$4" needle="$5"
  if [ "$want" != "$got" ]; then
    echo "FAIL: $name — expected exit $want, got $got"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
  fi
  if ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL: $name — output missing '$needle'"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
  fi
  echo "ok: $name"; pass=$((pass + 1))
}

new_repo() { # -> prints the path of an empty repository with one commit
  local dir; dir="$(mktemp -d)"
  git -C "$dir" init -q
  git -C "$dir" -c user.name=t -c user.email=t@t commit -q --allow-empty -m c0
  echo "$dir"
}

commit() { git -C "$1" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "$2"; }

run() { # <repo> -> sets OUT/RC
  OUT="$(cd "$1" && bash "$script" 2>&1)"
  RC=$?
}

# Two tags on one commit: the higher one is the base, not the one `git describe` would pick.
repo="$(new_repo)"
git -C "$repo" tag v4.8.0
git -C "$repo" tag v4.8.1
commit "$repo" c1
run "$repo"
check "two tags on one commit resolve to the higher" 0 "$RC" "$OUT" "4.8.2-ci"
[ "$OUT" = "4.8.2-ci" ] || { echo "FAIL: output is exactly the version — got '$OUT'"; fail=$((fail + 1)); }
rm -rf "$repo"

# Version order, not lexical order: v4.10.0 is newer than v4.9.9.
repo="$(new_repo)"
git -C "$repo" tag v4.9.9
commit "$repo" c1
git -C "$repo" tag v4.10.0
run "$repo"
check "tags compare by version, not lexically" 0 "$RC" "$OUT" "4.10.1-ci"
rm -rf "$repo"

# A tag that is not an ancestor of HEAD (a release cut after this branch forked) is not the base.
repo="$(new_repo)"
git -C "$repo" tag v1.2.3
git -C "$repo" checkout -q -b side
commit "$repo" side
git -C "$repo" tag v9.0.0
git -C "$repo" checkout -q -
commit "$repo" main
run "$repo"
check "an unreachable tag is ignored" 0 "$RC" "$OUT" "1.2.4-ci"
rm -rf "$repo"

# Tags that are not bare vMAJOR.MINOR.PATCH never become the base.
repo="$(new_repo)"
git -C "$repo" tag v2.0.0
git -C "$repo" tag v3.0.0-rc1
git -C "$repo" tag v9
run "$repo"
check "non-release tag shapes are ignored" 0 "$RC" "$OUT" "2.0.1-ci"
rm -rf "$repo"

# No release tag at all: a loud failure, never a guessed version.
repo="$(new_repo)"
run "$repo"
check "no reachable release tag fails" 1 "$RC" "$OUT" "no vMAJOR.MINOR.PATCH tag is reachable"
rm -rf "$repo"

# A shallow clone may be missing the tag that should win: refused.
repo="$(new_repo)"
git -C "$repo" tag v1.0.0
commit "$repo" c1
commit "$repo" c2
shallow="$(mktemp -d)"
git clone -q --depth 1 "file://$repo" "$shallow/clone"
run "$shallow/clone"
check "a shallow clone is refused" 1 "$RC" "$OUT" "shallow clone"
rm -rf "$repo" "$shallow"

echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
