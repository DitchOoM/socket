#!/usr/bin/env bash
# Native macOS test-harness fixture — homebrew socat + nginx in place of the
# Docker / Colima VM. See HANDOFF.md (Q1) for the rationale: Apple K/N tests
# only need an HTTP/TLS peer, not toxiproxy/netem (which are Linux-kernel-
# bound). Phase 3/4 fault-injection tests on macOS skip via the existing
# isToxiproxyAvailable() / isNetemAvailable() guards.
#
# Reproduces the L0 (echo, http) + L6 (TLS cert matrix) services from
# test-harness/docker-compose.yml on 127.0.0.1, on the same ports the Docker
# stack uses so HarnessConfig is platform-agnostic.

set -euo pipefail

# Repo root — this script lives at test-harness/macos/up.sh
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HARNESS_DIR="$REPO_ROOT/test-harness"
CERTS_DIR="$HARNESS_DIR/tls/certs"
WWW_DIR="$HARNESS_DIR/http/www"
RUN_DIR="/tmp/socket-test-harness"

mkdir -p "$RUN_DIR"

# ── homebrew ──────────────────────────────────────────────────────────────────
# socat + nginx are pre-installed on GitHub macos-latest runners. The
# `|| brew install` is a safety net for local dev / image rebuilds.
brew list socat >/dev/null 2>&1 || brew install socat
brew list nginx >/dev/null 2>&1 || brew install nginx

# ── TLS certs ─────────────────────────────────────────────────────────────────
bash "$HARNESS_DIR/tls/gen-certs.sh"

# ── CA trust (keychain + JVM keystore) ────────────────────────────────────────
# Keychain covers Apple Network.framework / NSURLSession (K/N targets).
# JVM keystore covers any JVM tests running on the same runner.
sudo security add-trusted-cert -d -r trustRoot \
    -k /Library/Keychains/System.keychain "$CERTS_DIR/ca.crt"

sudo keytool -delete -alias harness-root \
    -keystore "$JAVA_HOME/lib/security/cacerts" \
    -storepass changeit 2>/dev/null || true
sudo keytool -importcert -trustcacerts \
    -file "$CERTS_DIR/ca.crt" \
    -alias harness-root \
    -keystore "$JAVA_HOME/lib/security/cacerts" \
    -storepass changeit -noprompt

# ── echo (L0) — socat fork-per-connection ─────────────────────────────────────
# Mirrors test-harness/echo/Dockerfile: -T 60 inactivity timeout, fork on
# accept. Background; PID captured for down.sh.
nohup socat -T 60 TCP-LISTEN:14000,reuseaddr,fork EXEC:cat \
    >"$RUN_DIR/echo.log" 2>&1 &
echo $! > "$RUN_DIR/echo.pid"

# ── nginx (L0 http + L6 tls) ──────────────────────────────────────────────────
# One config inlining both vhosts. Paths point at the repo's
# test-harness/{tls/certs,http/www} so we reuse the same cert matrix and
# static bodies the Docker stack serves — no shadow copies.
cat > "$RUN_DIR/nginx.conf" <<EOF
worker_processes 1;
error_log $RUN_DIR/nginx-error.log;
pid $RUN_DIR/nginx.pid;
events { worker_connections 64; }
http {
    access_log $RUN_DIR/nginx-access.log;
    default_type application/octet-stream;

    # ── http (L0) ────────────────────────────────────────────────────────────
    server {
        listen 14080 default_server;
        server_name _;
        root $WWW_DIR;
        add_header Access-Control-Allow-Origin "*" always;
        add_header Access-Control-Allow-Methods "GET, POST, OPTIONS" always;
        add_header Access-Control-Allow-Headers "*" always;
        location / { index index.html; }
        location = /get  { default_type text/plain;       return 200 "ok\n"; }
        location = /json { default_type application/json; return 200 '{"ok":true}'; }
        location = /large { default_type text/plain;      alias $WWW_DIR/large; }
    }

    # ── tls cert matrix (L6) ─────────────────────────────────────────────────
    # Mirrors test-harness/tls/conf.d/default.conf — same ports (14443/14453/
    # 14463/14473/14483/14493), same cert files, same response bodies.
    server {
        listen 14443 ssl default_server;
        server_name valid.test localhost;
        root $WWW_DIR;
        ssl_certificate     $CERTS_DIR/valid.crt;
        ssl_certificate_key $CERTS_DIR/valid.key;
        location = /get  { default_type text/plain;       return 200 "ok\n"; }
        location = /json { default_type application/json; return 200 '{"ok":true}'; }
        location = /large { default_type text/plain;      alias $WWW_DIR/large; }
        location / { default_type text/plain; return 200 "tls-valid\n"; }
    }
    server {
        listen 14453 ssl;
        server_name self-signed.test;
        ssl_certificate     $CERTS_DIR/self-signed.crt;
        ssl_certificate_key $CERTS_DIR/self-signed.key;
        location / { default_type text/plain; return 200 "tls-self-signed\n"; }
    }
    server {
        listen 14463 ssl;
        server_name expired.test;
        ssl_certificate     $CERTS_DIR/expired.crt;
        ssl_certificate_key $CERTS_DIR/expired.key;
        location / { default_type text/plain; return 200 "tls-expired\n"; }
    }
    server {
        listen 14473 ssl;
        server_name other.test;
        ssl_certificate     $CERTS_DIR/wrong-host.crt;
        ssl_certificate_key $CERTS_DIR/wrong-host.key;
        location / { default_type text/plain; return 200 "tls-wrong-host\n"; }
    }
    server {
        listen 14483 ssl;
        server_name untrusted-root.test;
        ssl_certificate     $CERTS_DIR/untrusted-root.crt;
        ssl_certificate_key $CERTS_DIR/untrusted-root.key;
        location / { default_type text/plain; return 200 "tls-untrusted-root\n"; }
    }
    server {
        listen 14493 ssl;
        server_name valid.test localhost;
        ssl_certificate     $CERTS_DIR/valid.crt;
        ssl_certificate_key $CERTS_DIR/valid.key;
        ssl_protocols       TLSv1.3;
        location = /get { default_type text/plain; return 200 "ok\n"; }
        location / { default_type text/plain; return 200 "tls-valid-tls13\n"; }
    }
}
EOF

# nginx daemonizes by default after fork; -p sets the working/pid dir.
nginx -p "$RUN_DIR" -c "$RUN_DIR/nginx.conf"

# ── readiness probe ───────────────────────────────────────────────────────────
# Wait for echo + http + tls-valid to all answer before tests start. socat
# fork-accept is up immediately; nginx takes a beat after daemonizing.
for i in $(seq 1 30); do
    if nc -z 127.0.0.1 14000 \
       && nc -z 127.0.0.1 14080 \
       && nc -z 127.0.0.1 14443; then
        echo "macOS harness up: socat:14000 nginx:14080/14443-14493"
        exit 0
    fi
    sleep 0.2
done

echo "macOS harness failed readiness probe" >&2
exit 1
