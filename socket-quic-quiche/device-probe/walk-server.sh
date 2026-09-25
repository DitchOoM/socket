#!/usr/bin/env bash
# Sourced. Loads the walk echo server from local, untracked config: walk-server.env next to this
# file (or the file WALK_SERVER_ENV names). See walk-server.env.example. A variable already set in the
# environment wins over the file, so one invocation can point elsewhere without editing it.
WALK_SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WALK_SERVER_ENV="${WALK_SERVER_ENV:-$WALK_SERVER_DIR/walk-server.env}"
if [ -f "$WALK_SERVER_ENV" ]; then
  _ws_host="${SERVER_HOST-}"; _ws_port="${SERVER_PORT-}"; _ws_ssh="${SERVER_SSH-}"
  # shellcheck source=/dev/null
  . "$WALK_SERVER_ENV"
  if [ -n "$_ws_host" ]; then SERVER_HOST="$_ws_host"; fi
  if [ -n "$_ws_port" ]; then SERVER_PORT="$_ws_port"; fi
  if [ -n "$_ws_ssh" ]; then SERVER_SSH="$_ws_ssh"; fi
  unset _ws_host _ws_port _ws_ssh
fi

# walk_server_require <VAR>… — exits the calling script, with the one-line fix, unless every named
# variable is set. There is no built-in server to fall back to.
walk_server_require() {
  local v missing=()
  for v in "$@"; do [ -n "${!v-}" ] || missing+=("$v"); done
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "FATAL: walk server not configured (${missing[*]} unset). Fix: cp $WALK_SERVER_DIR/walk-server.env.example $WALK_SERVER_ENV and set your server in it." >&2
    exit 1
  fi
}
