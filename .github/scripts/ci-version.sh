#!/usr/bin/env bash
#
# Prints the version a non-release CI build uses: the highest vMAJOR.MINOR.PATCH tag reachable from
# HEAD, patch-bumped, with a `-ci` pre-release suffix (v4.19.0 -> 4.19.1-ci). It is a function of the
# commit alone, so every job that derives it for the same commit agrees, and the suffix keeps it from
# ever matching a version published to Maven Central.
#
# "Highest reachable" is chosen by version order, not by `git describe`: two tags on one commit
# (v4.8.0 and v4.8.1 both tag the same commit here) make describe pick the older one.
#
# Needs the full history with tags (actions/checkout `fetch-depth: 0`); a shallow clone is refused
# rather than answered from whatever tags it happens to carry.
set -euo pipefail

if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  echo "ci-version: $(pwd) is a shallow clone; check out with fetch-depth: 0 so every tag reachable from HEAD is present" >&2
  exit 1
fi

latest="$(git tag --list 'v*' --merged HEAD --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sed -n 1p || true)"
if [ -z "$latest" ]; then
  echo "ci-version: no vMAJOR.MINOR.PATCH tag is reachable from $(git rev-parse HEAD)" >&2
  exit 1
fi

IFS=. read -r major minor patch <<<"${latest#v}"
echo "${major}.${minor}.$((10#$patch + 1))-ci"
