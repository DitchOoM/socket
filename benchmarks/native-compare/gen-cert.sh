#!/bin/sh
# Writes cert.crt (X.509 v3, SAN localhost) and cert.key into <dir>, reusing the RSA key of the repo's test
# certificate so key type and size match the one this library is benchmarked with. rustls rejects the repo's
# v1 certificate, so every reference program uses this one instead.
set -e
dir="${1:?usage: gen-cert.sh <dir>}"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$dir"
cp "$repo/socket-quic-quiche/testcerts/cert.key" "$dir/cert.key"
openssl req -x509 -new -key "$dir/cert.key" -sha256 -days 3650 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" -out "$dir/cert.crt"
