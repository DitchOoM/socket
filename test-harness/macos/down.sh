#!/usr/bin/env bash
# Tear down the native macOS test-harness fixture. Pairs with up.sh.
# `if: always()` in CI — run this on test failure too so the next job gets
# clean ports.

set -euo pipefail

RUN_DIR="/tmp/socket-test-harness"

if [[ -f "$RUN_DIR/echo.pid" ]]; then
    kill "$(cat "$RUN_DIR/echo.pid")" 2>/dev/null || true
fi

if [[ -f "$RUN_DIR/nginx.pid" ]]; then
    nginx -p "$RUN_DIR" -c "$RUN_DIR/nginx.conf" -s stop 2>/dev/null || true
fi

# Stragglers — socat fork children, or a daemonized nginx that lost its pidfile.
pkill -f 'socat -T 60 TCP-LISTEN:14000' 2>/dev/null || true
pkill -f "nginx.*$RUN_DIR/nginx.conf" 2>/dev/null || true

rm -rf "$RUN_DIR"
echo "macOS harness down"
