#!/usr/bin/env bash
# Diagnose the Apple QUIC -9808 (errSSLBadCert) gap — macOS only.
#
# Background: the cross-platform `QuicHarnessIntegrationTests` runs against a LOCAL
# quic-echo peer and is deterministic on JVM/Linux/Android, but SKIPS on Apple K/N
# because Network.framework's QUIC TLS rejects the harness cert with errSSLBadCert
# (-9808). We want to un-skip it (full cross-platform parity, local + deterministic),
# which needs Apple to accept the LOCAL harness cert.
#
# This script pins down WHICH layer rejects, so we know the fix:
#   * If `security verify-cert -p ssl` PASSES once the CA is system-trusted, then the
#     cert itself is fine and Apple's *general* TLS trust accepts it — the remaining
#     -9808 is specific to Network.framework's QUIC trust evaluation, i.e. we need a
#     verify_block / NW trust config (Track B), NOT a cert change.
#   * If it FAILS, `security` prints the exact reason (expired, missing AKI, CT,
#     policy) — a LOCAL, deterministic fix in gen-certs.sh (e.g. the SKI/AKI hardening
#     this branch adds, or whatever else it reports).
#
# Run on a Mac:  bash test-harness/tls/diagnose-apple-quic-trust.sh
set -uo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "This diagnostic is macOS-only (uses the Security framework). Skipping on $(uname -s)."
    exit 0
fi

cd "$(dirname "$0")"
HOST="localhost" # valid.crt SANs: valid.test, localhost, 127.0.0.1 — pick one to hostname-match

echo "==> 1. (Re)generate the harness cert matrix"
bash gen-certs.sh --force

CA="$PWD/certs/ca.crt"
LEAF="$PWD/certs/valid.crt"
KEYCHAIN="/Library/Keychains/System.keychain"

echo "==> 2. Trust the harness CA in the System keychain (admin root → CT-exempt per Apple policy)"
echo "    (sudo required; we remove it again at the end)"
sudo security add-trusted-cert -d -r trustRoot -k "$KEYCHAIN" "$CA"

cleanup() {
    echo "==> Cleanup: remove the harness CA from the System keychain"
    sudo security delete-certificate -c "harness-root" "$KEYCHAIN" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> 3. THE DISCRIMINATOR: evaluate the leaf under the SSL policy (this is what TLS uses)"
echo "    security verify-cert -c valid.crt -p ssl -s $HOST"
if security verify-cert -c "$LEAF" -p ssl -s "$HOST"; then
    cat <<'MSG'

RESULT: SSL-policy trust PASSED.
  → The cert + chain are accepted by Apple's general TLS trust (CA trusted, CT-exempt
    as an admin root, attributes OK). So a remaining QUIC -9808 is NOT a cert problem —
    it is Network.framework's QUIC path not honoring system trust without a verify_block.
  → Action: pursue Track B (make the test-only sec_protocol verify_block work without the
    SIGABRT) — the gen-certs.sh hardening alone will not un-skip Apple. Confirm by step 4.
MSG
else
    cat <<'MSG'

RESULT: SSL-policy trust FAILED (reason printed above by `security`).
  → The cert/chain itself is rejected. This is a LOCAL, deterministic fix in gen-certs.sh.
    Common causes + fixes:
      - "missing AuthorityKeyIdentifier"/SKI  → the SKI/AKI extensions this branch adds
      - leaf validity > 398 days              → already capped at 397
      - missing serverAuth EKU / SAN mismatch → already set
      - Certificate Transparency required     → ensure the CA is an ADMIN root (trustRoot,
                                                 System keychain) so it's CT-exempt
  → Action: address the printed reason in gen-certs.sh, re-run this script until it PASSES,
    THEN un-skip QuicHarnessIntegrationTests on Apple.
MSG
fi

cat <<'MSG'

==> 4. (Optional) Definitive QUIC-path check — capture the real Network.framework error.
    In one terminal:
      log stream --predicate 'subsystem == "com.apple.network"' --info --debug
    In another, temporarily remove the Apple early-return in QuicHarnessIntegrationTests
    (the `if (appleSystemTrust) { println(... SKIP ...); return }` block) and run:
      ./gradlew :socket-quic:macosArm64Test --tests "*QuicHarnessIntegrationTests*"
    Then read the nw_connection "failed" state — its nw_error carries the OSStatus (-9808)
    and the underlying SecTrust result, which says definitively whether it's CT, anchor,
    hostname, or extension related. That decides cert-fix (gen-certs.sh) vs Track B.
MSG
