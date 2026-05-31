# Slice 3 — Multi-socket UdpChannel + path management (connection migration, rung 2)

Status: **design, pre-implementation.** Bindings (slices 1+2) are merged. This doc
designs the driver work and the public API, and is gated behind the test-infra
decision (now made — see "Test infra" below).

## Goal

Client-driven **active connection migration**: move a live quiche connection from
local path A to a freshly-opened local path B via `probe → VALIDATED → migrate`,
keep streams flowing across the switch, and survive passive rebinds the engine
notices on its own. This is rung 2 (single active path at a time) — **not** MP-QUIC
(rung 3, blocked at the engine; see `project_quic_phase3_migration`).

Out of scope for slice 3: Apple (Network.framework migrates internally, no
controllable API) and JS/Node (no API). `migrate()` throws
`UnsupportedOperationException` there.

## Test infra (decided)

**Loopback-alias in-repo harness first; netns+netem as a follow-on PR.**

- Active migration needs only **two distinct local addresses**, not NAT/netem.
  All of `127.0.0.0/8` is loopback on Linux, so a client can bind path A to
  `127.0.0.1` and path B to `127.0.0.2`, both reaching a server on
  `127.0.0.1:port`. Migration A→B is then exercisable in a plain `jvmTest` /
  `linuxX64Test` with no root, Docker, or namespaces. quiche's server
  auto-responds to PATH_CHALLENGE during normal recv/send, and our server already
  uses an unconnected `recvFrom` socket, so it accepts the new 4-tuple natively.
- **Passive rebind** (server-side `PeerMigrated`, lossy links) is deferred to a
  separate netns+veth+`tc netem` Linux CI job (sub-slice 3f). Not needed to
  unblock active migration.
- quic-interop-runner/ns-3 is the gold standard for **interop conformance** but
  requires an HTTP/0.9 shim + a Docker endpoint image and runs out-of-band from
  gradle — a later standalone milestone, not the prerequisite harness.

## Current single-path architecture (what changes)

- One `DatagramChannel` → one `NioUdpChannel`/`IoUringUdpChannel` → one
  `udpReaderLoop`.
- `flushOutgoing()` calls `connSend(conn, sendAddr, …, sendInfo)` and **ignores
  `sendInfo.from`** — it always writes to the single channel.
- `recvInfo` is built once at connect with `to` = path A's local addr, fixed for
  the connection's life.
- The driver **never polls `connPathEventNext`** — slices 1+2 bindings are unused.

## The active-migration flow (target behaviour)

1. Connected on path A (socket A, local `lA`, peer `p`).
2. App calls `migrate(localHost, localPort)` (or `migrate()` for an ephemeral
   rebind).
3. Driver checks `connAvailableDcids(conn) > 0` — active migration needs a spare
   destination CID. quiche issues NEW_CONNECTION_ID automatically post-handshake;
   if zero, fail fast (or wait one RTT).
4. Driver opens socket B (local `lB`, same peer) via an injected
   `UdpChannelFactory`, builds a per-path `recvInfo_B` (`to = lB`), and starts a
   reader loop for B.
5. Driver calls `connProbePath(conn, lB, lenB, p, lenP, seqOut)` → quiche queues a
   PATH_CHALLENGE for path B.
6. `flushOutgoing()` reads `sendInfo.from` for each datagram and routes it to the
   socket whose local addr matches — the PATH_CHALLENGE egresses **socket B**.
7. quiche validates B (CHALLENGE/RESPONSE). Driver polls `connPathEventNext` →
   `New(lB,p)` then `Validated(lB,p)`.
8. On `Validated(lB)` matching the pending target, driver calls
   `connMigrate(conn, lB, lenB, p, lenP, seqOut)` → active path becomes B; emits
   the new 4-tuple on `pathState`; completes the `migrate()` deferred.
9. App data now egresses socket B (new DCID on the new path). Socket A is torn
   down after quiche emits `Closed` for it (or a linger timeout) — never before,
   or an in-flight PATH_RESPONSE could be dropped.

## Four hard problems → solutions

### 1. Egress routing by `sendInfo.from`
quiche fills `sendInfo.from` with the local address each datagram must leave from.
Today it's ignored. **New binding (slice 3a):** `sendInfoFromAddr(info): Long` +
`sendInfoFromAddrLen(info): Int` — exact mirror of the existing
`sendInfoToAddr`/`ToAddrLen` (used server-side for `sendTo`). `flushOutgoing()`
decodes `from` → `PathKey` → looks up the socket in `paths[key]`.

### 2. Ingress attribution
quiche needs `recvInfo.to` = the local addr the packet arrived on. With two paths,
that varies per packet. **Solution: one `recvInfo` per path** (`to` = that path's
local addr), and `QuicheCmd.RecvPacket` gains a `pathKey`. Reader loop for path X
tags its packets with X; `execute()` picks `recvInfo_X`. (Cleaner than mutating a
shared `recvInfo.to`, and keeps the sockaddr-pinning lifecycle per-path.)

### 3. sockaddr **decoding** (the reverse of `SockAddrUtil.toNativeSockAddr`)
Path events and `sendInfo.from` hand back raw `sockaddr`/`sockaddr_storage`. Need:
- `sockAddrKey(addr, len): PathKey` — allocation-free identity (family, port,
  addr bytes) for the hot-path egress lookup.
- `fromNativeSockAddr(addr, len): InetSocketAddress` — for the public `pathState`
  surface.

Must handle the same family-byte variants the encoder does: BSD `sin_len`,
`AF_INET6` = 10 (Linux) / 30 (BSD) / 23 (Windows), port in network order. JVM
version in `commonJvmMain`; K/N version in `linuxMain`.

### 4. Socket creation lives in platform code
The driver is `commonMain`; opening a `DatagramChannel` (JVM) or io_uring fd
(Linux) is platform-specific. **New injected dependency:**

```kotlin
interface UdpChannelFactory {
    // Open a client UDP socket bound to localHost:localPort (null/0 = ephemeral),
    // connected to the same peer. Returns the channel + its resolved local
    // sockaddr (native addr+len, pinned) for quiche, + a release hook.
    suspend fun openPath(localHost: String?, localPort: Int): NewPath
}
class NewPath(
    val channel: UdpChannel,
    val localSockAddrAddress: Long,
    val localSockAddrLength: Int,
    val release: () -> Unit,
)
```

## Type changes summary

| Type | Change |
|---|---|
| `QuicheApi` (+ all 4 impls + `StubQuicheApi`) | + `sendInfoFromAddr`, `sendInfoFromAddrLen` |
| `SockAddrUtil` (commonJvmMain) + new `CinteropSockAddr` (linuxMain) | + `sockAddrKey`, `fromNativeSockAddr` |
| new `PathKey` (commonMain) | value class: family + port + addr bytes |
| new `UdpChannelFactory` / `NewPath` (commonMain + JVM/linux actuals) | open a new path socket |
| `QuicheCmd.RecvPacket` | + `pathKey` |
| `QuicheDriver` | `paths: Map<PathKey, PathEntry>`, per-path recvInfo + reader loop, `drainPathEvents()`, migration state machine, egress routing |
| `QuicScope` | + `suspend fun migrate(localHost: String? = null, localPort: Int = 0): MigrationResult`; + `val pathState: StateFlow<PathInfo>` |

`QuicScope` stays `commonMain`-safe: `migrate` takes `String?`/`Int` (no
`java.net.*`); `PathInfo`/`MigrationResult` are plain common types.

## Driver migration state machine

Per pending migration: `Probing → Validated → Migrated` | `Failed`.
- `migrate()` → check dcids, open B, build `recvInfo_B`, start reader B,
  `connProbePath(lB)`, record `pendingTarget = keyB`, return a `Deferred`.
- `drainPathEvents()` in `afterCommand()` (after `flushOutgoing` +
  `discoverNewStreams` — events are produced during `connRecv`/`connSend`):
  - `New(local,peer)` — record availability.
  - `Validated(local,peer)` — if `local == pendingTarget`: `connMigrate(lB)`,
    update `pathState`, complete deferred, schedule path-A teardown.
  - `FailedValidation(local)` — tear down socket B, complete deferred(failure).
  - `Closed(local)` — tear down that path's socket + reader loop + recvInfo.
  - `PeerMigrated(local,peer)` — passive: update recorded peer addr + `pathState`.
  - `ReusedSourceConnectionId` — log only (binding zeroes its addresses).

## Lifecycle / ownership

Each `PathEntry` owns its `UdpChannel`, reader `Job`, `recvInfo`, and pinned local
sockaddr holder. `cleanup()` iterates `paths` and frees all — extends the existing
`onCleanup` sockaddr-pinning rule (ffi.rs:2059 panic) per-path. Old path is torn
down only on `Closed` or linger-timeout, never eagerly.

## Sub-slices (small commits / reviewable PRs)

- **3a — bindings + decoding (no driver changes).** `sendInfoFromAddr/Len` across
  all 4 impls + stub; `sockAddrKey`/`fromNativeSockAddr` + `PathKey`; encode→decode
  round-trip unit tests (v4/v6 × family-byte variants). Compiles on all targets.
- **3b — loopback harness.** Test helper binding client paths to distinct loopback
  addrs; echo server gains a test-only "whoami" reply (client's observed peer addr)
  so the test proves bytes egress socket B. RED until 3c/3d land.
- **3c — multi-socket driver.** `UdpChannelFactory`, per-path `recvInfo`,
  `RecvPacket.pathKey`, egress routing by `sendInfo.from`. Single path still works
  (one-entry `paths` map). No public API yet.
- **3d — path-event consumption + migration state machine.** `drainPathEvents()`,
  probe→Validated→migrate, FailedValidation/Closed teardown.
- **3e — public API.** `QuicScope.migrate()` + `pathState`; plumb through
  `JvmQuicConnection` and the linux actual. Wire 3b harness green.
- **3f — passive rebind (separate PR).** netns+veth+`tc netem` Linux CI job for
  server-side `PeerMigrated` + lossy-link behaviour.

## Local validation matrix (per slice)

```
:socket-quic:compileKotlinJvm  compileTestKotlinJvm  compileJava21KotlinJvm
:socket-quic:compileKotlinLinuxX64  compileTestKotlinLinuxX64  ktlintCheck
```
The JNI C shim only builds in CI. **Remember:** every new `QuicheApi` method
breaks `StubQuicheApi` — always compile the `*Test*` tasks, not just main
(prior-slice lesson).

## Open questions / risks

- **Spare DCID required.** Active migration needs `connAvailableDcids > 0`. Server
  issues NEW_CONNECTION_ID post-handshake; gate `migrate()` on it.
- **`disable_active_migration` must be false** (default on both ends).
- **First-datagram routing.** Handshake datagrams' `sendInfo.from` = path A; the A
  `PathKey` is registered at connect time so the lookup always resolves. If a
  `from` key is unknown (shouldn't happen), fall back to the active path + log.
- **io_uring path sockets.** `IoUringUdpChannel` is a connected single fd; opening
  a second connected socket on a different local addr is fine — needs
  `IoUringUdpChannelFactory.openPath`.
