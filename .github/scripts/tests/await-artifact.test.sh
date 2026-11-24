#!/usr/bin/env bash
#
# Tests for await-artifact.sh, driven by a fake `gh` on PATH that answers from a canned listing.
#
# The assumption under test cost two runs (Validate Artifacts on #1046, Windows JVM Tests on #1048): the listing shows an
# artifact as soon as its upload begins, and a download in that window answers "Artifact not found". Only an artifact
# whose upload is finalized, which is when it has its digest, may count.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="${AWAIT_ARTIFACT_SCRIPT:-$here/../await-artifact.sh}"
pass=0
fail=0

check() { # <name> <expected-exit> <actual-exit> <output> [<must-contain>]
  local name="$1" want="$2" got="$3" out="$4" needle="${5:-}"
  if [ "$want" != "$got" ]; then
    echo "FAIL: $name — expected exit $want, got $got"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
  fi
  if [ -n "$needle" ] && ! grep -qF "$needle" <<<"$out"; then
    echo "FAIL: $name — output missing '$needle'"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
  fi
  echo "ok: $name"; pass=$((pass + 1))
}

run() { # <artifacts json> <jobs json> -> sets OUT/RC; the timeout is 0, so one poll decides
  local artifacts="$1" jobs="$2"
  local tmp; tmp="$(mktemp -d)"
  # The listing's own count, as the API gives it, of every artifact listed: finalized or not.
  jq '. + {total_count: (.artifacts | length)}' <<<"$artifacts" >"$tmp/artifacts.json"
  printf '%s' "$jobs" >"$tmp/jobs.json"
  cat >"$tmp/gh" <<'GH'
#!/usr/bin/env bash
# gh api <path> [--paginate] --jq <expr>: the listing the path names, through jq.
path=""; expr=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jq) expr="$2"; shift ;;
    repos/*) path="$1" ;;
  esac
  shift
done
case "$path" in
  *"/artifacts"*) jq -r "$expr" "$FAKE_DIR/artifacts.json" ;;
  *"/jobs"*) jq -r "$expr" "$FAKE_DIR/jobs.json" ;;
esac
GH
  chmod +x "$tmp/gh"
  OUT="$(PATH="$tmp:$PATH" FAKE_DIR="$tmp" GITHUB_RUN_ID=1 GITHUB_REPOSITORY=o/r GH_TOKEN=x \
         bash "$script" natives "natives job" 0 2>&1)"
  RC=$?
  rm -rf "$tmp"
}

running='{"jobs":[{"name":"natives job","status":"in_progress","conclusion":null}]}'

run '{"artifacts":[{"name":"natives","digest":"sha256:0f1e"}]}' "$running"
check "a finalized artifact counts" 0 "$RC" "$OUT" "natives is in this run"

run '{"artifacts":[{"name":"natives","digest":null}]}' "$running"
check "an artifact still uploading (no digest) does not count" 1 "$RC" "$OUT" "did not appear"

run '{"artifacts":[{"name":"natives","digest":""}]}' "$running"
check "an artifact with an empty digest does not count" 1 "$RC" "$OUT" "did not appear"

run '{"artifacts":[{"name":"natives"}]}' "$running"
check "an artifact listed without a digest field does not count" 1 "$RC" "$OUT" "did not appear"

run '{"artifacts":[{"name":"natives","digest":null}]}' '{"jobs":[{"name":"natives job","status":"completed","conclusion":"failure"}]}'
check "a producer that ended with its upload unfinished fails at once" 1 "$RC" "$OUT" "ended (failure) without uploading natives"

echo "await-artifact: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
