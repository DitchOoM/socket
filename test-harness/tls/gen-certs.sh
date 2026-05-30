#!/usr/bin/env bash
# Generate the harness TLS cert matrix (Phase 2).
# Idempotent: skips if certs/.generated exists (--force to regenerate).
# Output: test-harness/tls/certs/
#   ca.crt / ca.key                 — harness-root CA (the one we want platforms to trust)
#   untrusted-ca.crt / .key         — a DIFFERENT CA, not in any trust store
#   valid.{crt,key}                 — signed by harness-root, SAN 127.0.0.1 + localhost + valid.test
#   self-signed.{crt,key}           — self-signed (no CA in chain)
#   expired.{crt,key}               — signed by harness-root, backdated validity (already expired)
#   wrong-host.{crt,key}            — signed by harness-root, SAN is other.test (no 127.0.0.1)
#   untrusted-root.{crt,key}        — signed by untrusted-ca
#
# These cover the cert-matrix scenarios listed in TESTING_STRATEGY.md §2b.
# The CA is *not* committed — Gradle invokes this script on demand.

set -euo pipefail

cd "$(dirname "$0")"
CERT_DIR="certs"
mkdir -p "$CERT_DIR"

FORCE="${1:-}"
if [[ "$FORCE" != "--force" && -f "$CERT_DIR/.generated" ]]; then
    echo "harness certs already present in $CERT_DIR (pass --force to regenerate)"
    exit 0
fi

# Clean slate
rm -rf "$CERT_DIR"
mkdir -p "$CERT_DIR"
cd "$CERT_DIR"

# ── harness-root CA — the cert authority we want every platform to TRUST ──────
openssl genrsa -out ca.key 2048 2>/dev/null
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 -out ca.crt \
    -subj "/CN=harness-root" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"

# ── untrusted-root CA — a DIFFERENT CA, deliberately not given to platforms ───
openssl genrsa -out untrusted-ca.key 2048 2>/dev/null
openssl req -x509 -new -nodes -key untrusted-ca.key -sha256 -days 3650 -out untrusted-ca.crt \
    -subj "/CN=untrusted-root" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"

# helper: sign a leaf CSR with the given CA, attaching v3 extensions
sign_leaf() {
    local name="$1" cn="$2" sans="$3" ca_crt="$4" ca_key="$5"
    openssl genrsa -out "$name.key" 2048 2>/dev/null
    openssl req -new -key "$name.key" -out "$name.csr" -subj "/CN=$cn"
    cat > "$name.ext" <<EOF
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = $sans
EOF
    # 397 days: Apple's TLS stack (Network.framework / Security) rejects any
    # server *leaf* cert with validity > 398 days (errSSLBadCert / -9808),
    # which blocked the macOS QUIC handshake. The CA stays long-lived (the 398
    # rule is leaf-only). CI regenerates certs every run, so 397d never expires
    # in practice. BoringSSL/JVM (Linux harness) don't care about the shorter
    # validity, so this is safe cross-platform.
    openssl x509 -req -in "$name.csr" -CA "$ca_crt" -CAkey "$ca_key" -CAcreateserial \
        -out "$name.crt" -days 397 -sha256 -extfile "$name.ext" 2>/dev/null
    rm -f "$name.csr" "$name.ext"
}

# ── valid ──────────────────────────────────────────────────────────────────────
sign_leaf valid "valid.test" "DNS:valid.test,DNS:localhost,IP:127.0.0.1" ca.crt ca.key

# ── self-signed (leaf is its own root, no CA chain) ───────────────────────────
openssl genrsa -out self-signed.key 2048 2>/dev/null
openssl req -x509 -new -nodes -key self-signed.key -sha256 -days 3650 -out self-signed.crt \
    -subj "/CN=self-signed.test" \
    -addext "basicConstraints=critical,CA:FALSE" \
    -addext "keyUsage=critical,digitalSignature,keyEncipherment" \
    -addext "extendedKeyUsage=serverAuth" \
    -addext "subjectAltName=DNS:self-signed.test,DNS:localhost,IP:127.0.0.1"

# ── expired — signed by harness-root with backdated validity ──────────────────
# openssl x509 -req has no -enddate; use `openssl ca` with an inline config.
mkdir -p ca-db && touch ca-db/index.txt && echo 01 > ca-db/serial
cat > expired-ca.cnf <<EOF
[ca]
default_ca = CA_default
[CA_default]
dir              = .
database         = ca-db/index.txt
serial           = ca-db/serial
new_certs_dir    = ca-db
certificate      = ca.crt
private_key      = ca.key
default_md       = sha256
policy           = policy_any
unique_subject   = no
copy_extensions  = copy
[policy_any]
commonName = supplied
EOF
openssl genrsa -out expired.key 2048 2>/dev/null
openssl req -new -key expired.key -out expired.csr \
    -subj "/CN=expired.test" \
    -addext "subjectAltName=DNS:expired.test,DNS:localhost,IP:127.0.0.1" \
    -addext "extendedKeyUsage=serverAuth"

# YYYYMMDDHHMMSSZ (openssl 1.1.1+); fall back to YYMMDDHHMMSSZ if needed
not_before=$(date -u -d "2 years ago" "+%Y%m%d%H%M%SZ" 2>/dev/null || \
             date -u -v-2y "+%Y%m%d%H%M%SZ")
not_after=$(date -u -d "1 day ago"   "+%Y%m%d%H%M%SZ" 2>/dev/null || \
            date -u -v-1d            "+%Y%m%d%H%M%SZ")

openssl ca -batch -config expired-ca.cnf \
    -in expired.csr -out expired.crt \
    -startdate "$not_before" -enddate "$not_after" 2>&1 |
    grep -vE "^Using configuration|^Check that|^Certificate Details|^        |^[ -]+$" || true

rm -rf ca-db expired-ca.cnf expired.csr

# ── wrong-host — signed by harness-root, SAN is other.test (no 127.0.0.1) ─────
sign_leaf wrong-host "other.test" "DNS:other.test" ca.crt ca.key

# ── untrusted-root — signed by the *other* CA ─────────────────────────────────
sign_leaf untrusted-root "untrusted-root.test" \
    "DNS:untrusted-root.test,DNS:localhost,IP:127.0.0.1" \
    untrusted-ca.crt untrusted-ca.key

# Cleanup serial files openssl drops
rm -f ./*.srl

touch .generated
echo "OK: harness TLS cert matrix generated in $(pwd)"
ls -1 ./*.crt | sed 's|^\./|  |'
