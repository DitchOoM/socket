# RFC — Composable transport selection & fallback

**Status:** MVP implemented (`com.ditchoom.socket.transport` — `FallbackTransport`, `FallbackPolicy`, `CapabilityCache`, `NetworkId`, `TransportSet`/`defaultTransportChain`); §5 racing and the wired two-scope cache are v2
**Builds on:** [`RFC_UNIFIED_ESTABLISHMENT.md`](./RFC_UNIFIED_ESTABLISHMENT.md) — this RFC is the *selection/composition* layer that sits on top of the `Transport` / `SessionTransport` model, the `SessionOwningByteStream` projection (§3.3), reconnection (§5), and the typed error vocabulary (§6) defined there.

## 1. Goal

A multiplatform protocol library (MQTT first, but transport-agnostic) should obtain a connection with **one call** and get the best transport that actually works *right now, on this platform, to this server, over this network* — automatically preferring native-optimized transports and degrading to universally-compatible ones. Concretely, the desired preference order is:

```
QUIC  →  WebTransport  →  TCP  →  WebSocket
```

The protocol never learns which one won: it receives a `ByteStream` (and frames on top via its own `Codec`, exactly as `websocket`'s `connectWebSocket(transport: ByteStream, codec)` and socket's `CodecConnection` already do).

## 2. Two axes — and why they map to two *different* mechanisms

This is distinct from RFC_UNIFIED's single-stream-vs-multiplexed axes. The selection problem has its own two:

- **Axis A — client capability (static, platform-scoped).** The web cannot do raw QUIC or raw TCP; those `Transport` implementations *do not exist in the JS source set*. This is not a runtime decision — it's a compile-time fact expressed by KMP source sets.
- **Axis B — server + path capability (dynamic, connect-time).** "This server only speaks WebSocket," or "this network blocks UDP," is discovered by attempting to connect and observing the failure.

**Conflating them is the trap.** Axis A is a *filter*; Axis B is a *fallback loop*. They compose cleanly:

> **The platform picks the candidate *set*; the server/network picks the *winner* within it.**

### 2.1 One global ranking, filtered per platform

The preference order is defined **once** as a single ranking. Each platform's default chain is that ranking minus what it can't compile:

```kotlin
// commonMain
expect fun defaultTransportChain(config: TransportConfig): List<Transport>

// nativeMain / mobile:  [ QuicTransport, WebTransportTransport, TcpTransport, WebSocketTransport ]
// jsMain (web):         [                WebTransportTransport,               WebSocketTransport ]
```

Web isn't "trying and failing" QUIC — QUIC is never a candidate there. `WebSocket` is present in **every** platform's list: it is the universal floor (443 + HTTP upgrade, traverses any proxy), which is what makes "some servers only do WebSocket" a non-event.

### 2.2 Why this order

`QUIC-family before TCP-family, leanest-first within each family`:

| Transport | Family | Why here |
|---|---|---|
| QUIC | UDP/QUIC | leanest UDP path, direct control; connection **migration** (survives Wi-Fi↔cellular), 0-RTT, no head-of-line blocking — the mobile win is session *survival*, not just throughput |
| WebTransport | UDP/QUIC | QUIC dressed as HTTP/3: nearly all of QUIC's wins, traverses HTTP/3 infra/CDNs, and is the *only* QUIC option on web |
| TCP | TCP | reliable, universal on native; HoL-blocked, no migration |
| WebSocket | TCP | universal floor; most overhead, maximum compatibility |

WebTransport-before-TCP is safe even where few servers run it: an absent WebTransport endpoint yields a *fast, cacheable capability error* (RST / HTTP 404 on the H3 endpoint), not a timeout — so we drop to TCP cheaply and only "pay" for the preference when WebTransport is actually there.

## 3. The composition primitive: `FallbackTransport`

Everything reduces to `Transport` (RFC_UNIFIED §3.1), so the combinator is small and protocol-agnostic:

```kotlin
class FallbackTransport(
    private val chain: List<Transport>,
    private val policy: FallbackPolicy,
    private val cache: CapabilityCache,
    private val clock: Clock,           // injected for testability (virtual time)
) : Transport {
    override suspend fun connect(host: String, port: Int, config: TransportConfig): ByteStream {
        val candidates = cache.order(host, config.networkId, chain)   // demote known-unsupported
        val failures = mutableListOf<TransportFailure>()
        // staggered race across *families* (see §5); sequential within a family
        for (attempt in stagger(candidates, policy.staggerDelay)) {
            try {
                val stream = attempt.transport.connect(host, port, config)
                cache.recordSuccess(host, config.networkId, attempt.transport)
                return stream
            } catch (e: Throwable) {
                val verdict = policy.classify(e)                       // §4
                if (verdict.cacheUnsupported) cache.recordUnsupported(verdict.scope, host, config.networkId, attempt.transport)
                if (!verdict.fallback) throw e                         // fatal (auth, TLS-cert): stop, don't mask
                failures += TransportFailure(attempt.transport, e, verdict)
            }
        }
        throw TransportsExhausted(host, port, failures)
    }
}
```

`SessionTransport`-based transports (QUIC, WebTransport) enter the chain as `Transport` via the existing `SessionOwningByteStream` projection (RFC_UNIFIED §3.3): establish → open one bidirectional stream → `ByteStream` that closes the session when the stream closes.

## 4. `FallbackPolicy` — the error taxonomy is the whole ballgame

You **cannot** tell "server doesn't support X" from "the network blipped" by the *outcome*; you tell them apart by the **error class** (built on RFC_UNIFIED §6's typed vocabulary). Fallback is a 2×2, not one boolean:

| failure class | fall back *now*? | cache as *unsupported*? |
|---|---|---|
| connection refused / RST | yes | **yes** (per-host) — deterministic server signal |
| ALPN mismatch / protocol/version error | yes | **yes** (per-host) |
| HTTP 404/426 on H3/WS endpoint | yes | **yes** (per-host) |
| **timeout / unreachable / no-route / DNS** | yes | **no** — transient/ambiguous, says nothing about capability |
| TLS certificate / auth failure | **no** (fatal) | no — would fail on every rung and mask a real problem, or silently downgrade |

Key rule: **only deterministic capability errors ever poison the cache.** A router reboot, Wi-Fi uplink drop, or captive portal produces *timeouts*, which fall forward (so the user still connects) but are **never** recorded as "unsupported." So the "skipped a transport that was only transiently down" problem cannot arise by construction.

The genuinely-ambiguous case — a timeout could be transient *or* a firewall blackholing the UDP port — is resolved conservatively: **treat ambiguous as transient (never cache from a single timeout).** Permanently avoiding a good fast transport is worse than eating one bounded per-attempt timeout, and racing (§5) hides that latency. If blackholing proves common, add a *slow* signal later ("N consecutive timeouts over M connects → soft-demote with aggressive re-probe") — not in the MVP.

## 5. Staggered racing across families (not within)

QUIC and WebTransport **share fate** — both ride UDP/HTTP-3. On a UDP-blocked network both fail, as *timeouts*. Pure-sequential order then eats *two* full timeouts before reaching TCP.

- Racing QUIC vs WebTransport against each other is pointless (same UDP fate).
- The meaningful race is **UDP-family vs TCP-family**: give the UDP family a head start, but start the TCP family before the UDP family has fully timed out (RFC 8305 Happy Eyeballs, staggered). A UDP block then costs a stagger delay, not two timeouts.
- Default: ordered-with-per-attempt-timeout; opt-in staggered race with a small head start (e.g. 250 ms) for the preferred family. All delays injected via `clock` for deterministic tests.

## 6. `CapabilityCache` — two scopes, self-healing

The staggering insight reveals the cache has **two scopes**, because "unsupported" is sometimes about the server and sometimes about the path:

- **per-host**: "this *server* doesn't speak QUIC" — learned from RST/ALPN/404 (deterministic). Keyed on `host`.
- **per-network**: "this *path* blocks UDP" — the whole QUIC family timing out. Keyed on **network identity** (SSID / interface / `networkId`) and **invalidated on network change.**

This is exactly what makes the router scenario correct:
- Wi-Fi → cellular drops the per-network entry → QUIC is re-probed on the new path.
- A same-network router blip is a *timeout* → never becomes a per-host capability loss.

Both scopes are **hints, not hard exclusions**: every cached demotion carries a TTL + periodic re-probe (retry the full preferred chain every Nth connect / after TTL), so a server enabling QUIC later, or a fluke demotion, heals on its own.

## 7. Reconnection is a separate concern (RFC_UNIFIED §5)

Selection (this RFC) is about the *first* connect. A drop on an *established* connection goes through the existing `ReconnectingConnection`:

- On a drop, **retry the transport that was working first**, with exponential backoff — do not churn down the whole fallback chain hammering a dead network.
- Fall to a different transport only if re-establishing the working one returns a *capability* error (not a transient one) — same taxonomy as §4.
- Liveness (to notice the blip promptly and distinguish "peer gone" from "idle") comes from the protocol/transport keepalive (MQTT PINGREQ, WS ping/pong, QUIC PING) — out of scope here, noted as a dependency.

## 8. Invariant: TLS uniformity

The danger of a fallback chain is **silent downgrade**. Hard invariant: every rung is encrypted (QUIC's built-in TLS 1.3; `wss`, never `ws`; TCP+TLS). The policy treats a certificate/auth failure as **fatal** (§4) rather than a reason to try a weaker rung. Falling back must never weaken security.

## 9. Where each piece lives (no dependency cycles)

- `FallbackTransport`, `FallbackPolicy`, `CapabilityCache`, `defaultTransportChain` → **socket** (`transport` package). Protocol-agnostic; depends only on `Transport` + RFC_UNIFIED §6 errors.
- `WebSocketTransport : Transport` (the WS-payload-stream-as-`ByteStream` adapter — the inverse of today's `connectWebSocket(transport, codec)`) → **websocket** module.
- QUIC / WebTransport `Transport`s (via `SessionOwningByteStream`) → **socket-quic** / **socket-webtransport** (already exist).
- Consumers (mqtt) depend on **socket** (+ **websocket** for the WS rung). Direction: `buffer-flow ← socket ← websocket`; `mqtt → socket (+ websocket)`.

## 10. Test matrix (highest-value area — deterministic via the `Transport` seam)

The clean `Transport → ByteStream` boundary makes all of this testable without a real network, using a `ScriptedTransport` fake + `MemoryTransport` + virtual time (`runTest` + `TestCoroutineScheduler`).

1. **Policy unit tests (pure, exhaustive):** every error type → assert the correct `(fallback, cacheUnsupported, scope)` triple. Correctness lives here.
2. **`ScriptedTransport`** that fails on command: refuse / timeout / ALPN-mismatch / 404 / succeed-then-drop / **succeed-then-timeout-then-recover**.
3. **Cache-poisoning test (the router scenario):** QUIC works → inject a transient QUIC *timeout* while WS succeeds → assert cache did **not** demote QUIC → next connect still tries QUIC first. Contrast: QUIC *RST* → asserts per-host demote → and re-probe after TTL.
4. **Network-scope test:** whole QUIC family times out → per-network "UDP blocked" recorded → change `networkId` → assert QUIC re-probed (entry invalidated).
5. **Reconnection test:** established conn drops with transient error → same-transport retry with backoff, no chain fall-through; drops with capability error → falls through.
6. **Racing test:** UDP family stalls → assert TCP family started after stagger delay, not after full timeout; assert losers cancelled; assert no plaintext ever selected.
7. **Platform-filter test:** JS `defaultTransportChain` contains no QUIC/TCP; native contains all four; every list ends in WebSocket.
8. **Property/fuzz (optional):** random failure scripts → invariants: terminates, never selects plaintext, never poisons on a transient-only run.

## 11. Scope / phasing

- **MVP:** `Transport` chain + `FallbackTransport` (sequential, per-attempt timeout) + `FallbackPolicy` 2×2 + `WebSocketTransport` adapter + platform `defaultTransportChain`. Ships the QUIC→WebTransport→TCP→WebSocket behavior end-to-end.
- **v2:** staggered family racing; two-scope `CapabilityCache` with TTL + re-probe.
- **Later (only if measured):** Alt-Svc/proactive advertisement; slow N-strikes soft-demote for blackholed UDP.

## 12. Open questions — resolved

- ~~Does `socket-webtransport` have a **JS actual** over the browser WebTransport API?~~ **Resolved: yes** — `socket-webtransport` has `browserMain`/`jsMain`/`wasmJsMain` actuals over the browser WebTransport API (validated against real Chrome on js + wasmJs). §2.1's web chain has **two rungs**: WebTransport → WebSocket.
- ~~Addressing: capability bag vs. typed union?~~ **Resolved: neither — pre-addressed instances.** Each `Transport` carries its own per-transport addressing at construction (`WebSocketTransport(path, headers)` in the websocket repo, WebTransport URL path, QUIC ALPN); `connect(host, port, config)` stays uniform and socket never enumerates other modules' addressing needs. This gets the out-of-tree extensibility the bag was for with zero new API and no key registry. If per-connect addressing variation ever shows up, a typed-key extras bag on `TransportConfig` is the compatible follow-up.
- ~~Is `networkId` cheaply observable on every platform?~~ **Resolved by making absence first-class**: `NetworkId` is a sealed model where "can't identify the network" is the explicit `Unidentified` case that simply disables the per-network scope (no entry recorded or read), and browsers get the coarse `KindOnly` case (`navigator.connection.type`) — still enough for the decisive Wi-Fi↔cellular transition. The reactive `NetworkMonitor` (desktop-JVM FFM, wired into `ReconnectingConnection`) is the intended population source; wiring it to `TransportConfig.networkId` is v2 work alongside the two-scope cache.
