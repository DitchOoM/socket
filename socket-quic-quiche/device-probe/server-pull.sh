#!/usr/bin/env bash
# Pull the echo server's qlog to ./logs/<utc-stamp>-<tag>-server-qlog/, so it pairs with a walk's
# device pull by stamp (#624 item 3). Until now only the client side of a walk was recorded — the
# server's view of which packets it received and why it answered or did not was never on tape.
#
# Read-only against the server: unlike the device pull.sh, this never deletes anything remotely.
# /root/quic-echo-qlog is a live directory on a box shared with other production work, and the qlog
# budget there is currently uncapped (#624 item 1) — deleting from it is out of scope for a pull script.
set -euo pipefail

SERVER_USER="${SERVER_USER:-root}"
SERVER_HOST="${SERVER_HOST:-178.156.248.95}"
SERVER_SSH="${SERVER_SSH:-$SERVER_USER@$SERVER_HOST}"
SERVER_QLOG_DIR="${SERVER_QLOG_DIR:-/root/quic-echo-qlog}"
SERVER_APP_DIR="${SERVER_APP_DIR:-/root/quic-echo-test}"
SERVER_CONTAINER="${SERVER_CONTAINER:-quic-echo-test}"
# Files younger than this are still being appended to by an open connection; copying one mid-write
# would give a truncated, unparseable record instead of a legitimate partial one.
SKIP_AGE_SECONDS=60

TAG="${1:?usage: server-pull.sh <tag>}"
mkdir -p "$(dirname "$0")/logs"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$(dirname "$0")/logs/$stamp-$TAG-server-qlog"
mkdir -p "$out"

# One round trip: the remote clock's own "now" alongside every top-level sqlog's name/size/mtime,
# so the still-writing check is judged by the server's clock, not this machine's. -maxdepth 1: an
# archive-*/ subdirectory under the qlog dir holds an already-archived older walk and is not this
# pull's concern.
listing="$(ssh "$SERVER_SSH" bash -s -- "$SERVER_QLOG_DIR" <<-'REMOTE'
	set -euo pipefail
	dir="$1"
	date +%s
	find "$dir" -maxdepth 1 -type f -name '*.sqlog' -printf '%f\t%s\t%T@\n'
REMOTE
)"
now_epoch="$(printf '%s\n' "$listing" | head -1)"
files_raw="$(printf '%s\n' "$listing" | tail -n +2)"

ready_names=()
ready_bytes=0
skipped_n=0
min_mtime=""
max_mtime=""
while IFS=$'\t' read -r name size mtime; do
  [ -z "$name" ] && continue
  mtime_i="${mtime%%.*}"
  age=$(( now_epoch - mtime_i ))
  if [ "$age" -lt "$SKIP_AGE_SECONDS" ]; then
    skipped_n=$(( skipped_n + 1 ))
    continue
  fi
  ready_names+=("$name")
  ready_bytes=$(( ready_bytes + size ))
  if [ -z "$min_mtime" ] || [ "$mtime_i" -lt "$min_mtime" ]; then min_mtime="$mtime_i"; fi
  if [ -z "$max_mtime" ] || [ "$mtime_i" -gt "$max_mtime" ]; then max_mtime="$mtime_i"; fi
done <<< "$files_raw"

total_on_server=$(( ${#ready_names[@]} + skipped_n ))

if [ "$total_on_server" -eq 0 ]; then
  echo "no server qlog files in $SERVER_QLOG_DIR on $SERVER_SSH — a walk with no server capture is a finding, not a success." >&2
elif [ "${#ready_names[@]}" -eq 0 ]; then
  echo "$skipped_n sqlog file(s) on the server, but all modified within the last ${SKIP_AGE_SECONDS}s — skipped as still being written. Nothing pulled this run; try again shortly." >&2
else
  list_file="$(mktemp)"
  trap 'rm -f "$list_file"' EXIT
  printf '%s\n' "${ready_names[@]}" > "$list_file"
  rsync -az --files-from="$list_file" "$SERVER_SSH:$SERVER_QLOG_DIR/" "$out/"
  echo "pulled ${#ready_names[@]} qlog file(s), $ready_bytes bytes -> $out"
  echo "time range: $(date -u -r "$min_mtime" +%Y-%m-%dT%H:%M:%SZ) .. $(date -u -r "$max_mtime" +%Y-%m-%dT%H:%M:%SZ)"
  if [ "$skipped_n" -gt 0 ]; then
    echo "skipped $skipped_n file(s) modified within the last ${SKIP_AGE_SECONDS}s (still being written)"
  fi
fi

# Provenance: which server build produced these qlogs. A qlog with no record of the build that
# wrote it is much less useful than one paired with the jar's git sha and the container's own
# identity — both cheap to capture and easy to lose once the container is redeployed.
provenance_text="$(ssh "$SERVER_SSH" bash -s -- "$SERVER_APP_DIR" "$SERVER_CONTAINER" <<-'REMOTE'
	set -euo pipefail
	app_dir="$1"; container="$2"
	echo '## BUILD-INFO.txt'
	if [ -f "$app_dir/BUILD-INFO.txt" ]; then
	  cat "$app_dir/BUILD-INFO.txt"
	else
	  echo "(missing: $app_dir/BUILD-INFO.txt not present on the server)"
	fi
	echo
	echo "## docker inspect $container"
	docker inspect "$container" | python3 -c '
import json, sys
d = json.load(sys.stdin)[0]
print("image=" + d["Config"]["Image"])
print("image_id=" + d["Image"])
print("container_created=" + d["Created"])
print("container_started_at=" + d["State"]["StartedAt"])
'
REMOTE
)"
provenance_file="$out/provenance.txt"
{
  echo "server-pull provenance — $stamp $TAG"
  echo "pulled_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "server: $SERVER_SSH"
  echo "qlog_dir: $SERVER_QLOG_DIR (container path /app/qlog)"
  echo
  echo "$provenance_text"
} > "$provenance_file"
echo "provenance -> $provenance_file"
