---
sidebar_position: 2
title: Apple (iOS/macOS/tvOS/watchOS)
---

# Apple Platforms

Apple support splits by protocol, and the split matters:

| Protocol | Backend on Apple |
|----------|------------------|
| TCP + TLS | `NWConnection` / `NWListener` (Network.framework) |
| UDP | `NWConnection` in UDP mode for connected clients; a dual-stack POSIX socket for servers and multicast |
| **QUIC / HTTP&#8203;/3 / WebTransport** | **Cloudflare [quiche](https://github.com/cloudflare/quiche)**, compiled in and reached through cinterop — *not* Network.framework |

:::info QUIC on Apple is quiche, not `NWProtocolQUIC`
This library has **no** Network.framework-native QUIC backend. The module that would have provided
one (`socket-quic-nw`) was deleted in June 2026, and every platform — Apple included — runs the same
Cloudflare quiche engine. Network.framework's role in the QUIC stack is limited to carrying the
client's UDP datagrams (see [below](#datagram-path)).

Running one engine everywhere is deliberate: it is what makes connection migration, unreliable
datagrams, pluggable congestion control, and per-stream `RESET_STREAM`/`STOP_SENDING` behave
identically on Apple and everywhere else. `NWProtocolQUIC` exposes none of those knobs.
:::

## Supported Targets

- macOS (arm64, x64)
- iOS (arm64, simulator arm64, simulator x64)
- tvOS (arm64, simulator arm64, simulator x64)
- watchOS (arm64, simulator arm64, simulator x64)

## TCP and TLS

TCP connections and listeners are `NWConnection` and `NWListener`, reached from Kotlin/Native
through a small C shim rather than a Swift library. `src/nativeInterop/cinterop/nw_helpers.h`
bridges Network.framework's Objective-C and dispatch APIs to K/N-safe types (opaque handles,
plain callbacks), and the cinterop is wired per-target in `build.gradle.kts` via
`configureNWHelpersCinterop()`.

The Kotlin side lives in `src/appleNativeImpl/kotlin/com/ditchoom/socket/`:

- `NWClientSocketWrapper` — outbound TCP connections
- `NWSocketWrapper` — the shared read/write/close machinery
- `NWServerWrapper` — `NWListener` for accepting inbound connections

Received data is materialized from `NSData` inside the completion callback, so no copy is made on
the way to a `ReadBuffer`.

TLS is handled natively by Network.framework: when `SocketOptions` carries a non-null `TlsConfig`,
`NWProtocolTLS.Options` is configured on the connection parameters. Certificate validation therefore
goes through the **system keychain** — see [TLS](../core-concepts/tls) for what that implies for
private CAs.

### Read timeouts are non-destructive

Network.framework has no per-receive cancel. Rather than tear the connection down when a read times
out, the outstanding `nw_helper_tcp_receive` is left in flight and its one-shot completion is captured
in a socket-held `CompletableDeferred`; the caller only `withTimeout`s the `await`. A timed-out read
throws `SocketTimeoutException` and orphans the receive for the *next* `read()` to re-await. The
connection is cancelled only on genuine EOF, error, or `close()`.

## QUIC

`socket-quic` on Apple is quiche, exactly as on JVM, Android, and Linux. The Kotlin ↔ quiche binding
is `CinteropQuicheApi` (`socket-quic-quiche/src/appleMain/`), and the Apple targets link the real
`libquiche.a` — the shared `Quic*TestSuite` conformance suites run against it, not against a stub.

### Datagram path

QUIC needs a UDP socket underneath, and the two roles use different ones:

- **Client** — `NwUdpDatagramChannel`, an `NWConnection` in UDP mode. Network.framework is used here
  specifically because it reports path changes, which is what makes QUIC connection migration
  (RFC 9000 §9) react to a Wi-Fi → cellular switch on iOS instead of stalling.
- **Server** — `PosixUdpDatagramChannel`, a plain dual-stack POSIX socket. A server needs
  `recvfrom`-style unconnected receive to accept from arbitrary peers, and it must be dual-stack:
  Network.framework resolves `localhost` to `::1`, so a v4-only server would never be reached from
  an `NWConnection` client on the same machine.

Multicast uses `MulticastPosixUdpDatagramChannel`. Note that `SO_REUSEADDR` is applied for multicast
only — on Darwin as well as Linux — because for unicast it lets a more-specific bind steal delivery
from a wildcard socket.

### Certificate pinning caveats

When pinning or supplying your own anchors for QUIC on Apple, two constraints bite in practice:
`SecTrust` rejects leaf certificates valid for more than 398 days, and an anchor quiche will accept
must carry `CA:TRUE`. Both surface as opaque handshake failures if missed.

## Building

Apple targets require macOS with Xcode installed. There is no separate Swift build step — the C
shim is compiled by the Kotlin cinterop tooling as part of the normal Gradle build:

```bash
./gradlew macosArm64Test          # or macosX64Test
./gradlew iosSimulatorArm64Test
```

A full `./gradlew build` on macOS also builds quiche for each Apple target, which is the slow part
of a cold build.

## Requirements

- macOS with Xcode installed
- Rust toolchain (to build quiche for the Apple targets)
