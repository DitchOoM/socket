#!/usr/bin/env bash
# Pull the log to ./logs/<utc-stamp>-<tag>.log, and the per-connection replay traces beside it.
#
# The traces are the point of a walk now: the log says what happened, the traces let it be replayed
# through `TraceToFixture` without walking again. Pulled unconditionally — a walk whose traces are
# left on the device is a walk that has to be repeated.
set -euo pipefail
. "$(dirname "$0")/common.sh"
TAG="${1:-walk}"; mkdir -p "$(dirname "$0")/logs"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$(dirname "$0")/logs/$stamp-$TAG.log"
adbs pull "$DEVICE_LOG" "$out" >/dev/null && echo "pulled $(wc -l < "$out") lines -> $out"

# `|| true` on the listing, not on the pull: an empty traces dir is a legitimate state (a run that
# never connected), but a pull that fails after we have seen files is a real failure and must not be
# swallowed. ⚠️ A composite `shell: bash` step runs under -e, so a no-match here would kill the script.
if adbs shell "ls $DEVICE_TRACES/*.trace 2>/dev/null" | grep -q .; then
  traces="$(dirname "$0")/logs/$stamp-$TAG-traces"
  mkdir -p "$traces"
  adbs pull "$DEVICE_TRACES/." "$traces" >/dev/null
  n=$(find "$traces" -name '*.trace' | wc -l | tr -d ' ')
  bytes=$(find "$traces" -name '*.trace' -exec cat {} + | wc -c | tr -d ' ')
  echo "pulled $n replay trace(s), $bytes bytes -> $traces"
else
  echo "no replay traces on the device — the walk is NOT replayable. Check that the probe build carries the trace sink." >&2
fi

# quiche's own frame-level record, when the run had it on (start.sh turns it on): a run of .sqlog
# segments per connection, decrypted by quiche itself — evidence that does not pass through this
# library's code.
QLOG="/sdcard/Android/data/$PKG/files/qlog"
if adbs shell "ls $QLOG/*.sqlog 2>/dev/null" | grep -q .; then
  qlog="$(dirname "$0")/logs/$stamp-$TAG-qlog"; mkdir -p "$qlog"
  adbs pull "$QLOG/." "$qlog" >/dev/null
  echo "pulled $(find "$qlog" -name '*.sqlog' | wc -l | tr -d ' ') qlog file(s), $(du -sk "$qlog" | cut -f1) KB -> $qlog"
else
  echo "no qlog on the device (the run was started without it)"
fi

# TLS key logs (conn-v6-0003.keys beside conn-v6-0003.trace): with them Wireshark decrypts the traces' datagrams
# with no code of ours in the loop. They decrypt the walk, so the probe keeps them in the app's PRIVATE
# files dir, reachable only through run-as (the test APK is debuggable), and so does this copy: 0700.
# `previous` holds key logs a START moved aside; removed from the phone only after a successful pull.
KEYS_FOUND="$(adbs shell "run-as $PKG ls files 2>/dev/null" | tr -d '\r' | grep -xE 'keys|previous' | tr '\n' ' ' || true)"
if [ -n "$KEYS_FOUND" ]; then
  keys="$(dirname "$0")/logs/$stamp-$TAG-keys"; mkdir -p "$keys"; chmod 700 "$keys"
  # shellcheck disable=SC2086 # KEYS_FOUND is a word list on purpose
  if adbs exec-out "run-as $PKG tar -cf - -C files $KEYS_FOUND" | tar -xf - -C "$keys"; then
    echo "pulled $(find "$keys" -name '*.keys' | wc -l | tr -d ' ') key log(s) -> $keys (SECRET: decrypts the walk)"
    case " $KEYS_FOUND " in *" previous "*) adbs shell "run-as $PKG rm -rf files/previous" ;; esac
  else
    echo "key logs on the device could NOT be pulled — left in place: files/{$KEYS_FOUND}" >&2
  fi
else
  echo "no key logs on the device (the run was started without them)"
fi

# Runs a START moved aside instead of deleting (PREVIOUS-RUN in the log). Pulled, then removed
# from the device only once the pull returned success, so nothing is ever lost between the two.
PREV="/sdcard/Android/data/$PKG/files/previous"
if adbs shell "ls $PREV 2>/dev/null" | grep -q .; then
  prev="$(dirname "$0")/logs/$stamp-$TAG-previous"; mkdir -p "$prev"
  if adbs pull "$PREV/." "$prev" >/dev/null; then
    echo "pulled previous run(s): $(ls "$prev" | tr '\n' ' ') -> $prev"
    adbs shell "rm -rf $PREV"
  else
    echo "previous run(s) on the device could NOT be pulled — left in place: $PREV" >&2
  fi
fi
