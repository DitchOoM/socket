#!/usr/bin/env bash
# Pull the echo server's qlog to ./logs/<utc-stamp>-<tag>-server-qlog/, so it pairs with a walk's
# device pull by stamp (#624).
#
# Every file is taken, including the ones a live connection is still writing: a settled file is
# copied whole, a live one as a snapshot of the bytes it held when listed — quiche only ever appends,
# so those bytes are final — cut back to its last complete record. A walk's longest-lived connection
# is usually still echoing when the pull runs, and its record is the one a walk exists for.
# MANIFEST.tsv says which file was which, with its size and mtime on the server.
#
# A connection's qlog is a run of segments (<name>.sqlog, then <name>_seg0002.sqlog and on); the
# server keeps them under its budget and names everything it dropped in qlog-budget.log, which is
# pulled too, together with the server's own START line (its build and capture config).
#
# Read-only against the server: it deletes nothing there. What the server keeps is its budget's
# decision, not this script's.
set -euo pipefail

SERVER_USER="${SERVER_USER:-root}"
SERVER_HOST="${SERVER_HOST:-178.156.248.95}"
SERVER_SSH="${SERVER_SSH:-$SERVER_USER@$SERVER_HOST}"
SERVER_QLOG_DIR="${SERVER_QLOG_DIR:-/root/quic-echo-qlog}"
SERVER_APP_DIR="${SERVER_APP_DIR:-/root/quic-echo-test}"
SERVER_CONTAINER="${SERVER_CONTAINER:-quic-echo-test}"
# A file modified more recently than this may still be appended to, so it is snapshotted rather than
# copied whole.
SETTLED_AGE_SECONDS=60

TAG="${1:?usage: server-pull.sh <tag>}"
mkdir -p "$(dirname "$0")/logs"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$(dirname "$0")/logs/$stamp-$TAG-server-qlog"
mkdir -p "$out"
manifest="$out/MANIFEST.tsv"
printf 'file\tstate\tbytes_on_server\tbytes_pulled\tmodified_utc\n' > "$manifest"

utc() { date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ; }

# Cut $1 back to its last complete JSON-SEQ record (every record ends in a newline) and print the
# bytes kept. Reads backwards from the end, so a snapshot of hundreds of MB costs one small read.
cut_to_last_record() {
  python3 - "$1" <<'PY'
import os, sys
path = sys.argv[1]
size = os.path.getsize(path)
with open(path, "rb") as f:
    end = size
    keep = 0
    while end > 0:
        start = max(0, end - (1 << 20))
        f.seek(start)
        chunk = f.read(end - start)
        i = chunk.rfind(b"\n")
        if i >= 0:
            keep = start + i + 1
            break
        end = start
os.truncate(path, keep)
print(keep)
PY
}

# One round trip: the server's own clock, then every top-level qlog file and the notes, with size and
# mtime, so "still being written" is judged by the server's clock rather than this machine's.
# -maxdepth 1: an archive-*/ subdirectory holds an already-archived older walk.
listing="$(ssh "$SERVER_SSH" bash -s -- "$SERVER_QLOG_DIR" <<-'REMOTE'
	set -euo pipefail
	dir="$1"
	date +%s
	find "$dir" -maxdepth 1 -type f \( -name '*.sqlog' -o -name 'qlog-budget.log' \) -printf '%f\t%s\t%T@\n'
REMOTE
)"
now_epoch="$(printf '%s\n' "$listing" | head -1)"
files_raw="$(printf '%s\n' "$listing" | tail -n +2)"

settled_names=(); settled_sizes=(); settled_mtimes=()
live_names=(); live_sizes=(); live_mtimes=()
while IFS=$'\t' read -r name size mtime; do
  [ -z "$name" ] && continue
  mtime_i="${mtime%%.*}"
  if [ $(( now_epoch - mtime_i )) -lt "$SETTLED_AGE_SECONDS" ]; then
    live_names+=("$name"); live_sizes+=("$size"); live_mtimes+=("$mtime_i")
  else
    settled_names+=("$name"); settled_sizes+=("$size"); settled_mtimes+=("$mtime_i")
  fi
done <<< "$files_raw"

total=$(( ${#settled_names[@]} + ${#live_names[@]} ))
if [ "$total" -eq 0 ]; then
  echo "no server qlog files in $SERVER_QLOG_DIR on $SERVER_SSH — a walk with no server capture is a finding, not a success." >&2
fi

pulled_bytes=0
vanished=0

# Settled files, whole. rsync exit 24 is "a file vanished": the server's budget evicted it between the
# listing and the copy, which the manifest records rather than failing the pull over.
if [ "${#settled_names[@]}" -gt 0 ]; then
  list_file="$(mktemp)"
  trap 'rm -f "$list_file"' EXIT
  printf '%s\n' "${settled_names[@]}" > "$list_file"
  rc=0
  rsync -az --files-from="$list_file" "$SERVER_SSH:$SERVER_QLOG_DIR/" "$out/" || rc=$?
  if [ "$rc" -ne 0 ] && [ "$rc" -ne 24 ]; then
    echo "rsync failed ($rc) pulling settled qlog files" >&2
    exit "$rc"
  fi
  for i in "${!settled_names[@]}"; do
    name="${settled_names[$i]}"
    if [ -f "$out/$name" ]; then
      got="$(wc -c < "$out/$name" | tr -d ' ')"; state=settled
    else
      got=0; state=vanished; vanished=$(( vanished + 1 ))
    fi
    pulled_bytes=$(( pulled_bytes + got ))
    printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$state" "${settled_sizes[$i]}" "$got" "$(utc "${settled_mtimes[$i]}")" >> "$manifest"
  done
fi

# Live files, as a snapshot of the bytes they held when listed, cut back to the last whole record.
for i in "${!live_names[@]}"; do
  name="${live_names[$i]}"; size="${live_sizes[$i]}"
  remote_path="$(printf '%q' "$SERVER_QLOG_DIR/$name")"
  if ssh "$SERVER_SSH" "head -c $size -- $remote_path" > "$out/$name" 2>/dev/null; then
    got="$(cut_to_last_record "$out/$name")"; state=live-snapshot
  else
    rm -f "$out/$name"; got=0; state=vanished; vanished=$(( vanished + 1 ))
  fi
  pulled_bytes=$(( pulled_bytes + got ))
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$state" "$size" "$got" "$(utc "${live_mtimes[$i]}")" >> "$manifest"
done

if [ "$total" -gt 0 ]; then
  echo "pulled ${#settled_names[@]} settled + ${#live_names[@]} live-snapshot file(s), $pulled_bytes bytes -> $out"
  echo "manifest: $manifest"
  if [ "${#live_names[@]}" -gt 0 ]; then
    echo "live snapshots end at their last whole record; their connections were still writing when pulled"
  fi
  if [ "$vanished" -gt 0 ]; then
    echo "$vanished file(s) vanished between listing and copy — evicted by the server's budget; see qlog-budget.log" >&2
  fi
fi

# The server's own account of itself: its START line (build + capture config) and every qlog line it
# printed. A server that records nothing says so here, the same day, instead of at the end of a walk.
ssh "$SERVER_SSH" "docker logs $SERVER_CONTAINER 2>&1" | grep -E '^(START|READY) |^\[qlog\] ' > "$out/server-stdout.txt" || true
last_start="$(grep '^START ' "$out/server-stdout.txt" | tail -1 || true)"
if [ -z "$last_start" ]; then
  echo "the server printed no START line: its jar predates build stamping, so which build wrote these qlogs is unrecorded" >&2
else
  echo "server: $last_start"
  case "$last_start" in *"qlog=off"*) echo "the server is NOT recording qlog (START says qlog=off)" >&2 ;; esac
  case "$last_start" in *"build=unknown"*) echo "the server's build is not stamped (${last_start%% port=*})" >&2 ;; esac
fi

# Provenance: which server build produced these qlogs, and the container's identity — cheap to
# capture and easy to lose once the container is redeployed.
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
print("env=" + " ".join(e for e in d["Config"]["Env"] if e.startswith("QUIC_")))
'
REMOTE
)"
provenance_file="$out/provenance.txt"
{
  echo "server-pull provenance — $stamp $TAG"
  echo "pulled_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "server: $SERVER_SSH"
  echo "qlog_dir: $SERVER_QLOG_DIR (container path /app/qlog)"
  echo "start: ${last_start:-(none)}"
  echo
  echo "$provenance_text"
} > "$provenance_file"
echo "provenance -> $provenance_file"
