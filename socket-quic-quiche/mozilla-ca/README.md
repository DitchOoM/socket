# Vendored Mozilla CA root bundle (iOS system trust)

`cacert.pem` is the Mozilla NSS root-certificate bundle maintained and republished by the curl
project (<https://curl.se/docs/caextract.html>). It is embedded into the Apple Kotlin/Native klib by
the `:socket-quic-quiche:generateMozillaCaRoots` Gradle task (which emits `MOZILLA_CA_ROOTS_PEM` into
`build/generated/mozilla-ca/`) and loaded as the TLS trust anchor set on the **iOS family** when
`verifyPeer = true` and the caller pins no anchors.

## Why it exists

iOS (unlike macOS) ships no filesystem CA store, and quiche/BoringSSL's compiled-in default verify
paths resolve nothing there — so `verifyPeer = true` fails every public-CA handshake (`tlsAlert 48`).
macOS keeps using its system store (`/etc/ssl/cert.pem`); only the iOS family loads this bundle.
See `WithQuicConnection.apple.kt` → `loadAppleSystemCaTrust`.

This is the interim "bundled roots" approach. The longer-term fix — delegating to the iOS keychain /
`SecTrust` so MDM-installed and OS-revoked roots are honoured and the set auto-updates — is tracked in
**#186**. Bundled roots are static: they do not reflect OS revocation or enterprise trust changes.

## Refreshing

1. Download the latest bundle **and** its published checksum:
   ```
   curl -fSL -o cacert.pem        https://curl.se/ca/cacert.pem
   curl -fSL -o cacert.pem.sha256 https://curl.se/ca/cacert.pem.sha256
   ```
2. Verify integrity (must match):
   ```
   shasum -a 256 cacert.pem    # compare against cacert.pem.sha256
   ```
3. Rebuild. `generateMozillaCaRoots` fails if the bundle has fewer than 100 certificate blocks
   (a truncated or corrupt download).

Current vendored bundle: see the `## Certificate data from Mozilla as of:` line at the top of
`cacert.pem`. Integrity checksum is recorded in `cacert.pem.sha256`.
