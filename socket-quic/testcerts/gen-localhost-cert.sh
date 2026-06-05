#!/usr/bin/env bash
# Regenerate the self-signed `localhost` test identity used by the QUIC CA-pinning tests
# (QuicServerTestSuite.pinnedCorrectCaAnchor... / pinnedWrongCaAnchor...).
#
# Why this exists / why it's committed rather than generated at build time:
#   Apple's Security framework (SecTrustEvaluateWithError) rejects any TLS server cert whose
#   validity exceeds 398 days as `errSecCertificateNotStandardsCompliant` — so the cert MUST be
#   short-lived to be trustable on Apple (quiche/BoringSSL on JVM/Linux is lenient and would
#   accept a long-lived one, which is how a 100-year cert silently passed CI on JVM/Linux while
#   hanging every Apple handshake). A short-lived cert can't be committed "forever", so
#   LocalhostCertFixtureGuardTest fails ~45 days before expiry telling you to re-run this script.
#   We keep it committed (not build-time-generated) so the cross-platform jvmTest — including the
#   Windows runner, which may not have openssl — needs no toolchain at test time.
#
# After running this, regenerate the p12 (`./gradlew :socket-quic:generateTestP12`) — it's
# git-ignored and rebuilt from these PEMs.
set -euo pipefail
cd "$(dirname "$0")"

openssl req -x509 -newkey rsa:2048 -sha256 -days 397 -nodes \
  -keyout localhost.key -out localhost.crt \
  -config <(cat <<'CNF'
[req]
distinguished_name=dn
x509_extensions=v3
prompt=no
[dn]
CN=localhost
[v3]
# CA:TRUE is required so quiche/BoringSSL accepts this self-signed cert as a pinned trust
# ANCHOR (a CA:FALSE leaf is rejected as an anchor → handshake closes). Apple is happy with a
# CA:TRUE self-signed cert as both server identity and anchor as long as validity ≤ 398 days
# (the -days 397 above) and serverAuth EKU + a matching SAN are present. keyCertSign accompanies
# CA:TRUE; digitalSignature/keyEncipherment cover the TLS-server-leaf role.
basicConstraints=critical,CA:TRUE
keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
CNF
)

# The fixture is duplicated per consumer source set (each reads its own classpath/cwd copy):
#   - testcerts/                          → Apple K/N tests + the generateTestP12 p12 source + Linux
#   - src/jvmTest/resources/certs/        → JVM (and the Windows jvmTest runner)
#   - src/androidInstrumentedTest/...     → Android instrumented tests
# Keep them byte-identical so cert-pinning behaves the same everywhere; this script is the one
# place that writes all of them. (LocalhostCertFixtureGuardTest also asserts they match.)
for dest in ../src/jvmTest/resources/certs ../src/androidInstrumentedTest/resources/certs; do
  cp localhost.crt localhost.key "$dest/"
done

echo "Regenerated localhost.crt (valid 397 days) + localhost.key in all 3 fixture dirs. Now run:"
echo "  ./gradlew :socket-quic:generateTestP12"
