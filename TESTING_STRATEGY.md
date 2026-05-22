# Testing Strategy: A Deterministic, Non-Flaky Multiplatform Test Suite

Status: DESIGN — nothing implemented yet.
Author: research pass, 2026-05-22.
Scope: `com.ditchoom:socket` + `socket-quic`, all targets (JVM, Android, JS Node, JS browser/Karma, wasmJs browser, Linux native, Apple).

---

## 0. Problem statement

`./gradlew allTests` is flaky. Two consecutive runs failed a disjoint set of tests. The root causes, confirmed by reading the test source:

1. **Public-internet dependency.** ~35 test methods connect to `google.com`, `www.cloudflare.com`, `example.com`, `httpbin.org`, `nginx.org`, `*.badssl.com`, `cloudflare-quic.com`. These fail on DNS hiccups, rate limiting, captive portals, IPv6-only CI, or simply when a remote endpoint changes its TLS config / HTTP body.
2. **Wall-clock timing assertions.** `ClientCancellationTests`, `IoUringCancelTests`, `IoUringManagerTests`, `ServerCancellationTests` assert `elapsed < 500ms` / `< 200ms`. Under WSL2 load these blow past the threshold (observed `9901ms`).
3. **OS/kernel socket-state timing.** `SimpleSocketTests.checkPort()` shells out to `lsof` and asserts zero sockets in `CLOSE_WAIT`. TCP teardown is asynchronous; a `CLOSE_WAIT` socket from a *just-finished* test is a race, not a bug.
4. **TCP-boundary decode races.** `tlsWithSni` decodes a live HTTP response as a strict UTF-8 string; a multi-byte char split across two TCP reads makes strict decoding throw.

The fix is a **local fault-injection harness** plus **assertion discipline** (observable state, not wall-clock budgets). The harness maps onto the three-layer model the user already settled on:

| Layer | Tool | Granularity | Fixes |
|-------|------|-------------|-------|
| **L3** | `tc qdisc … netem` | IP packet | latency, loss, jitter, reordering, connect-timeout |
| **L4** | `toxiproxy` | TCP connection | peer-close races, partial read/write, half-open, RST, slow-close, bandwidth throttle |
| **L6** | `badssl`-style cert matrix | TLS handshake | every `TlsErrorTests` scenario |
| **L0** | plain local server | in-process / container | everything that just needs *a* reachable peer |

---

## 1. Inventory

Legend for "Fix layer":
- **L0** = plain local TCP/HTTP server (the harness echo/http container, or an in-process `ServerSocket`).
- **L3** = netem (packet drop/delay).
- **L4** = toxiproxy (connection-level fault).
- **L6** = TLS cert matrix.
- **STATE** = not a network problem; rewrite the assertion to check observable state.

### 1a. Tests that reach a public host

| Test (file:line) | Host:port | Needs | Fix layer |
|---|---|---|---|
| `SimpleSocketTests.httpRawSocketExampleDomain` (`SimpleSocketTests.kt:117`) | example.com:80 | plain HTTP, body contains `<html>` | **L0** (HTTP container) |
| `SimpleSocketTests.httpRawSocketGoogleDomain` (`SimpleSocketTests.kt:120`) | google.com:80 | plain HTTP | **L0** |
| `SimpleSocketTests.httpsRawSocketGoogleDomain` (`SimpleSocketTests.kt:122`) | google.com:443 | HTTPS — already `@Ignore` | **L0 + L6** (valid-cert TLS), then un-ignore |
| `SimpleNioBlockingSocketTests` / `SimpleNioNonBlockingSocketTests` `httpRawSocket*` / `httpsRawSocket*` | delegate to above | — | inherits the fix |
| `NetworkIntegrationTests.tcp_connectToPublicServer` (`NetworkIntegrationTests.kt:17`) | example.com:80 | HTTP `HTTP/1.` prefix | **L0** |
| `NetworkIntegrationTests.tls_connectToPublicServer` (`:31`) | example.com:443 | TLS + HTTP | **L0 + L6** |
| `NetworkIntegrationTests.tls_connectToCloudflare` (`:45`) | cloudflare.com:443 | TLS + HTTP, SNI-strict | **L0 + L6** |
| `NetworkIntegrationTests.tcp_readWriteRoundtrip` (`:60`) | httpbin.org:443 | TLS + HTTP `200` | **L0 + L6** |
| `TlsErrorTests.tlsWithValidCertificate` (`TlsErrorTests.kt:62`) | www.google.com:443 | valid cert handshake | **L6** (valid) |
| `TlsErrorTests.tlsConnectionReuse` (`:87`) | www.google.com:443 ×3 | repeated valid handshake | **L6** (valid) |
| `TlsErrorTests.tlsWithSni` (`:113`) | www.cloudflare.com:443 | SNI-strict + **UTF-8 decode race** | **L6** (SNI vhost) + STATE (decode) |
| `TlsErrorTests.tlsToExampleDotCom` (`:172`) | www.example.com:443 | valid cert | **L6** |
| `TlsErrorTests.tlsToNginx` (`:196`) | nginx.org:443 | valid cert | **L6** |
| `TlsErrorTests.tlsToHttpbin` (`:220`) | httpbin.org:443 | valid cert + `/get` | **L6** |
| `TlsErrorTests.tlsConcurrentConnections` (`:244`) | httpbin.org:443 ×5 | 5 concurrent TLS | **L6** |
| `TlsErrorTests.tlsLargerResponse` (`:277`) | www.google.com:443 | >1KB response | **L6** (HTTP body sized by harness) |
| `TlsErrorTests.tlsJsonApi` (`:307`) | httpbin.org:443 | `/json` route | **L6** (harness serves `/json`) |
| `TlsErrorTests.tlsExpiredCertificateShouldFail` (`:338`) | expired.badssl.com:443 | expired cert rejected | **L6** (expired vhost) |
| `TlsErrorTests.tlsWrongHostShouldFail` (`:368`) | wrong.host.badssl.com:443 | wrong-host cert rejected | **L6** (wrong-host vhost) |
| `TlsErrorTests.tlsSelfSignedShouldFail` (`:398`) | self-signed.badssl.com:443 | self-signed rejected | **L6** (self-signed vhost) |
| `TlsErrorTests.tlsInsecureModeAllowsSelfSigned` (`:430`) | self-signed.badssl.com:443 | self-signed accepted w/ insecure | **L6** |
| `TlsErrorTests.tlsInsecureModeAllowsExpired` (`:454`) | expired.badssl.com:443 | expired accepted w/ insecure | **L6** |
| `TlsErrorTests.tlsDefaultOptionsRejectSelfSigned` (`:477`) | self-signed.badssl.com:443 | self-signed rejected | **L6** |
| `TlsErrorTests.tlsReconnectAfterClose` (`:509`) | httpbin.org:443 ×2 | TLS reconnect | **L6** |
| `TlsErrorTests.tlsReadIntoWriteBuffer` (`:551`) | example.com:443 | TLS WriteBuffer read path | **L6** |
| `TlsErrorTests.tlsFirstReadReturnsData` (`:593`) | example.com:443 | TLS 1.3 NewSessionTicket retry | **L6** (force TLS 1.3 vhost) |
| `TlsErrorTests.tlsBothReadOverloadsReturnSameData` (`:625`) | example.com:443 ×2 | both read overloads equal | **L6** |
| `TlsErrorTests.tlsMultipleSequentialReads` (`:686`) | www.google.com:443 | multi-read until EOF | **L6** |
| `TlsErrorTests.tlsToNonTlsPort` (`:29`) | example.com:80 | TLS to plain port → fail | **L0** (harness plain port) |
| `TlsErrorTests.nonTlsConnectionToTlsPort` (`:139`) | google.com:443 — currently commented out | plain to TLS port | **L6** |
| `SniStrictHostsTest.handshakeSucceedsAgainstSniStrictHosts` (`SniStrictHostsTest.kt:24`) | example.com / cloudflare.com / badssl.com :443 | SNI extension regression guard | **L6** (multi-vhost on one IP — *the* SNI test) |
| `LinuxTlsTests.tlsConnectionRequiresCACertificates` (`LinuxTlsTests.kt:12`) | www.google.com:443 | BoringSSL CA load works | **L6** (valid, harness root in trust store) |
| `QuicIntegrationTests.*` (`QuicIntegrationTests.kt:40`) | cloudflare-quic.com:443 | H3 QUIC handshake/streams | **L0** QUIC echo container (harness already has `QuicEchoTestServer`) |

**Count: 33 distinct public-host test methods** in the base module + **6** in `QuicIntegrationTests`. (`SimpleNio*` classes add 6 more *delegating* methods each, so the raw failing-surface is larger, but they all collapse onto the inventory above.)

Note `WrapJvmExceptionTests.kt:161` references `"example.com"` only as a *string argument* to a pure mapping function — **no network**, leave it alone.

### 1b. Tests that assert a wall-clock timing threshold

| Test (file:line) | Assertion | Fix |
|---|---|---|
| `ClientCancellationTests.cancelReadCompletesQuickly` (`SimpleSocketTests.kt:447`) | `elapsed < 500ms` | **STATE** — assert `readJob` completed + socket observably done; drop the budget (or CI-aware bound) |
| `ClientCancellationTests.cancelWriteCompletesQuickly` (`:510`) | `elapsed < 500ms` | **STATE** |
| `ClientCancellationTests.multipleCancellationsDontCrash` (`:617`) | `elapsed < 500ms` ×3 | **STATE** — keep the "doesn't crash / no leak" intent, drop the budget |
| `ServerCancellationTests.serverClosesQuickly` (`:384`) | `elapsed < 500ms` | **STATE** — assert `serverJob` joined + `!server.isListening()` |
| `IoUringCancelTests.cancelCompletesQuickly` (`IoUringCancelTests.kt:60`) | `< 200ms` | **STATE** (keep generous `< 5s` watchdog only) |
| `IoUringCancelTests.multipleCancelCyclesNoLeaks` (`:111`) | `< 500ms` ×5 | **STATE** — leak check is the real intent |
| `IoUringManagerTests.shutdownCompletesQuickly` (`IoUringManagerTests.kt:58`) | `< 500ms` | **STATE** — assert NOP-wakeup *happened* (poller thread state), not duration |
| `IoUringManagerTests.cleanupAndReinitializeWorks` (`:238`) | `< 500ms` ×3 | **STATE** — functional reuse is the intent |
| `IoUringManagerTests.cleanupCancelsPendingOperations` (`:299`) | `< 500ms` | **STATE** — `readException != null` is the intent |
| `IoUringManagerTests.rapidCleanupReinitializeCycles` (`:350`) | `< 500ms` ×10 | **STATE** |
| `IoUringManagerTests.rapidOpenCloseCycles` (`:178`) | `successCount >= 80%` | **borderline** — under WSL2 a ratio assertion is also flaky; see §5 |

`ServerConnectionTimingTest` (QUIC) is named "timing" but actually asserts *functional* outcomes inside a `withTimeout(15.seconds)` — it does **not** assert a tight budget. Leave it; it is correctly written.

### 1c. Tests that depend on OS/kernel socket-state timing

| Test (file:line) | Dependency | Fix |
|---|---|---|
| `SimpleSocketTests.checkPort()` → called by `readHttp`, `serverEcho`, `clientEcho`, `suspendingInputStream` (`SimpleSocketTests.kt:275-282`) | `lsof` post-test, asserts 0 sockets in `CLOSE_WAIT` | **STATE** — poll-with-timeout for `CLOSE_WAIT` to drain (kernel teardown is async), not a single-shot assert. This is the **exact** Run-1 `httpsRawSocketGoogleDomain` failure. |
| `ExceptionIntegrationTests.writeAfterPeerClose_producesSocketClosedException` (`ExceptionIntegrationTests.kt:147`) | races to overflow kernel send buffer (`100 × 8KB`) before peer-close is observed; raw `IOException: Broken pipe` escapes wrapping | **L4** — toxiproxy `down`/RST after the connect makes the peer-close *deterministic and immediate*; also a wrapper bug to fix (see §5) |
| `JvmExceptionSubtypeTests.brokenPipeOrReset_isSocketClosedSubtype` (`JvmExceptionSubtypeTests.kt:95`) | same `200 × 8KB` race | **L4** + wrapper fix |
| `ExceptionIntegrationTests.serverImmediateClose_clientReadProducesSocketClosedException` (`:108`) | `delay(100)` hope that RST landed | **L4** — toxiproxy deterministic close |
| `ExceptionIntegrationTests.readAfterPeerClose_producesSocketClosedException` (`:67`) | in-process server, fairly safe | **L0** (keep, but can use L4 for determinism) |
| `ResourceCleanupTests.coroutineCancellationCleansUpSocket` (`ResourceCleanupTests.kt:146`) | `withTimeout(30s)` wrapper expired under load — Run-2 failure | **STATE** — see §5; the test logic is fine, the parent `runTestNoTimeSkipping` 30s budget is what blew |
| `ResourceCleanupTests.timeoutDoesNotLeakResources` (`:229`) | `withTimeout(100ms)` on a read — tight, but the assertion is `!isOpen()` which is fine | low risk; keep |
| `WrapNodeErrorTests.connectionTimeout_producesSocketTimeoutException` (`WrapNodeErrorTests.kt:137`) | connects `10.255.255.1` non-routable; already catches alternative outcomes | low risk; keep (or **L3** netem `loss 100%` for a deterministic connect-timeout) |
| `SimpleSocketTests.connectTimeoutWorks` / `closeWorks` (`:26`,`:55`) | `10.255.255.1` non-routable, 1s timeout | **L3** — netem `loss 100%` on a harness port gives a *deterministic* connect timeout instead of relying on a magic IP |
| QUIC `*` server-suite tests with `delay(100)`/`delay(2.seconds)` "let it settle" | in-process, localhost UDP under WSL2 | mostly OK; flag for STATE review if they flake (use deferred/signals not sleeps) |

---

## 2. Harness design — local Docker Compose stack

A single `test-harness/docker-compose.yml`. All services on a user-defined bridge network `socketnet`. The design principle: **scenario = port**. A test picks behavior by connecting to a different port; no control API needed for the common path (control API only for dynamic netem changes — see §2c).

### 2a. Services

```yaml
# test-harness/docker-compose.yml
name: socket-test-harness
networks:
  socketnet: { driver: bridge }

services:
  # ---- L0: plain TCP echo ---------------------------------------------------
  echo:
    build: ./echo            # tiny Go/Rust binary: read -> write back, verbatim
    networks: [socketnet]
    ports:
      - "14000:14000"        # raw TCP echo

  # ---- L0: HTTP/1.1 server --------------------------------------------------
  http:
    image: nginx:1.27-alpine
    networks: [socketnet]
    volumes:
      - ./http/conf.d:/etc/nginx/conf.d:ro
      - ./http/www:/usr/share/nginx/html:ro   # /, /get, /json, a >1KB body for tlsLargerResponse
    ports:
      - "14080:80"

  # ---- L6: TLS cert matrix --------------------------------------------------
  # ONE container, ONE IP, MULTIPLE SNI vhosts + multiple ports.
  # This is the local 'badssl.com'. nginx with one server{} block per cert.
  tls:
    image: nginx:1.27-alpine
    networks:
      socketnet:
        aliases:                       # SNI test needs distinct names on one IP
          - valid.test
          - self-signed.test
          - expired.test
          - wrong-host.test
          - untrusted-root.test
    volumes:
      - ./tls/conf.d:/etc/nginx/conf.d:ro
      - ./tls/certs:/etc/nginx/certs:ro
    ports:
      - "14443:443"      # valid cert         (SNI: valid.test)
      - "14444:443"      # -> via SNI routing, see note
      # simpler: one listener per scenario on distinct ports:
      - "14443->443"     # valid
      - "14453"          # self-signed   (server block 2, listen 453)
      - "14463"          # expired       (server block 3, listen 463)
      - "14473"          # wrong-host    (server block 4, listen 473)
      - "14483"          # untrusted-root(server block 5, listen 483)
      - "14493"          # TLS-1.3-only  (ssl_protocols TLSv1.3)

  # ---- L4: toxiproxy --------------------------------------------------------
  toxiproxy:
    image: ghcr.io/shopify/toxiproxy:2.12.0
    networks: [socketnet]
    cap_add: [NET_ADMIN]   # also lets us run netem INSIDE this container (L3)
    ports:
      - "8474:8474"        # toxiproxy control API (HTTP/JSON)
      - "15000:15000"      # proxy -> echo        (faulted TCP echo)
      - "15080:15080"      # proxy -> http
      - "15443:15443"      # proxy -> tls:valid

  # ---- L0: QUIC echo (socket-quic) -----------------------------------------
  quic-echo:
    build: ./quic           # wraps the existing QuicEchoTestServer (jvmTest)
    networks: [socketnet]
    ports:
      - "14433:14433/udp"
```

**Port plan (host side):**

| Port | Service | Used by |
|---|---|---|
| `14000` | plain TCP echo | clientEcho/serverEcho-style L0 tests |
| `14080` | HTTP/1.1 | `httpRawSocket*`, `tcp_connectToPublicServer`, `tlsToNonTlsPort` |
| `14443` | TLS valid | `tlsWithValidCertificate`, `NetworkIntegrationTests.tls_*`, `LinuxTlsTests`, `httpsRawSocketGoogleDomain` |
| `14453` | TLS self-signed | `tlsSelfSigned*`, `tlsInsecureModeAllowsSelfSigned`, `tlsDefaultOptionsRejectSelfSigned` |
| `14463` | TLS expired | `tlsExpired*`, `tlsInsecureModeAllowsExpired` |
| `14473` | TLS wrong-host | `tlsWrongHostShouldFail` |
| `14483` | TLS untrusted-root | new coverage |
| `14493` | TLS-1.3-only | `tlsFirstReadReturnsData` |
| `15000` | toxiproxy → echo | partial-read/write, slow-close, RST tests |
| `15443` | toxiproxy → tls | `writeAfterPeerClose`, `brokenPipeOrReset` (toxiproxy `down`) |
| `8474` | toxiproxy API | dynamic L4 fault config |
| `14433/udp` | QUIC echo | `QuicIntegrationTests` |

> **SNI routing vs distinct ports.** Both are kept: distinct ports are the simplest (a test = a port, zero ambiguity). But `SniStrictHostsTest` specifically exists to verify the client *sends an SNI extension*. That test must hit **one IP, one port (14443), and rely on SNI hostname** to route to different vhosts — otherwise it does not test SNI at all. So the `tls` container runs nginx with `server_name`-based vhosts on `:443` **and** the per-scenario `listen` ports above. The SNI test connects to `14443` with hostnames `valid.test` / `wrong-host.test` etc.; everything else uses the per-scenario port.

### 2b. The cert matrix (L6)

Generated once by `test-harness/tls/gen-certs.sh` (committed script, generated certs **not** committed — built by a Gradle task, see §3d):

| Cert | CN / SAN | Signed by | Validity | Purpose |
|---|---|---|---|---|
| `valid` | `valid.test`, `localhost`, `127.0.0.1` | `harness-root` CA | now → +10y | success path |
| `self-signed` | `self-signed.test` | itself | now → +10y | self-signed rejection |
| `expired` | `expired.test` | `harness-root` CA | -2y → -1y | expiry rejection |
| `wrong-host` | CN `other.test` only | `harness-root` CA | now → +10y | hostname-mismatch rejection |
| `untrusted-root` | `untrusted-root.test` | a *different* CA not in trust store | now → +10y | unknown-CA rejection |

The `harness-root` CA cert must be injectable into each platform's trust store (see §3c) so the *valid* path actually validates. `self-signed` / `expired` / `wrong-host` / `untrusted-root` must **fail** with default `SocketOptions.tlsDefault()` and **pass** with `tlsInsecure()`.

### 2c. L3 — netem

netem is applied **inside the toxiproxy container** (it already has `cap_add: NET_ADMIN`) against its egress interface, or inside a dedicated `netem` sidecar. Two ways a test triggers it:

1. **Static, compose-time:** a separate compose profile / a second proxy port pre-configured with a fixed `tc` rule baked into the container entrypoint — e.g. port `15001` is "echo with 100% packet loss" for deterministic connect-timeout.
2. **Dynamic, test-time:** the toxiproxy container also runs a tiny HTTP `netem-control` shim (10 lines of shell behind `socat`, or reuse the *exact pattern* already in `socket-quic/.../netctrl/NetworkControlServer.kt` — that file is a TCP command server that runs network commands; generalize it). A test POSTs `{ "dev": "eth0", "rule": "delay 200ms" }` and the shim runs `tc qdisc replace …`.

Recommendation: **start with static** (profiles + fixed ports). Only build the dynamic control shim when a test genuinely needs to *change* conditions mid-connection. The `netctrl` protocol in `socket-quic/src/sharedJvmTestProtocol/` is the proven blueprint — it already has typed `AddLatency` / `BlockUdp` commands and a codec; lift it to a shared harness module rather than reinventing.

### 2d. L4 — toxiproxy usage

toxiproxy is configured per-test via its JSON API on `:8474`. A test (or a `@BeforeTest`) creates a proxy and attaches *toxics*:

- `writeAfterPeerClose` / `brokenPipeOrReset`: create proxy `tls→tls`, connect, then `POST /proxies/tls/toxics` a `down`/`reset_peer` toxic → the peer close is **immediate and deterministic**, no `100×8KB` send-buffer race.
- partial reads: `slicer` toxic (split a write into N pieces with `delay` between) → exercises the read-loop reassembly path deterministically (also covers the `tlsWithSni` UTF-8-straddle bug *on purpose*).
- half-open: `timeout` toxic.
- bandwidth: `bandwidth` toxic → exercises slow-transfer paths without netem.

This is the layer the WSL2-flaky `ExceptionIntegrationTests` need most: it converts "hope the kernel produced a broken pipe" into "the proxy *will* send a RST".

---

## 3. Multiplatform reachability — the hard part

The same `commonTest` assertions must run on JVM, JS Node, JS browser (Karma), wasmJs browser, Linux native, and Apple. The harness is a set of `host:port`s; every platform must (a) **discover** those endpoints and (b) **physically reach** them.

### 3a. Endpoint discovery — generated config, single source of truth

One file, `test-harness/harness.env`, is the source of truth:

```
HARNESS_HOST=127.0.0.1
ECHO_PORT=14000
HTTP_PORT=14080
TLS_VALID_PORT=14443
TLS_SELF_SIGNED_PORT=14453
TLS_EXPIRED_PORT=14463
TLS_WRONG_HOST_PORT=14473
TLS_UNTRUSTED_PORT=14483
TLS_TLS13_PORT=14493
TOXIPROXY_API=8474
TOXIPROXY_ECHO_PORT=15000
QUIC_ECHO_PORT=14433
```

A Gradle task `generateHarnessConfig` reads `harness.env` and **generates a Kotlin file into `commonTest`**:

```kotlin
// build/generated/harness/commonTest/.../HarnessConfig.kt   (generated, git-ignored)
package com.ditchoom.socket.harness
object HarnessConfig {
    const val host = "127.0.0.1"
    const val echoPort = 14000
    const val httpPort = 14080
    const val tlsValidPort = 14443
    // ...
}
```

Wire it into every test source set:

```kotlin
// build.gradle.kts
val generateHarnessConfig by tasks.registering(GenerateHarnessConfigTask::class) {
    envFile.set(layout.projectDirectory.file("test-harness/harness.env"))
    outputDir.set(layout.buildDirectory.dir("generated/harness/commonTest"))
}
kotlin.sourceSets.commonTest {
    kotlin.srcDir(generateHarnessConfig.map { it.outputDir })
}
tasks.withType<AbstractTestTask>().configureEach { dependsOn(generateHarnessConfig) }
```

Why a generated Kotlin object and not Gradle system properties:
- K/N (`linuxX64`, Apple) and wasmJs **cannot read JVM system properties**. `expect/actual` for "what port" would mean 7 hand-maintained actuals — fragile.
- A generated `commonMain`-of-`commonTest` object is visible to **every** target identically. Zero `expect/actual`.
- The host value still needs a per-platform override for browsers (see §3c) — handle that with **one** `expect fun harnessHost()` whose Linux/JVM/Apple actual returns `HarnessConfig.host` and whose browser actual returns `window.location.hostname`. That is the *only* expect/actual the harness needs.

### 3b. Browser / Karma reachability

This is the genuinely hard part. Karma launches a headless browser; `commonTest` runs *inside* the browser sandbox.

- **Raw TCP/TLS/QUIC are impossible in a browser.** The codebase already knows this — `getNetworkCapabilities()` returns `WEBSOCKETS_ONLY` for browser/wasmJs and `runTestNoTimeSkipping` swallows `UnsupportedOperationException`. So **browser/wasmJs targets never exercise the socket harness directly.** Do not try to make them. The conformance suite (§4) must keep the `WEBSOCKETS_ONLY` early-return so browser runs stay green by skipping socket scenarios.
- **What browsers *can* reach:** the `http` container, over `fetch`/WebSocket, IF served same-origin or CORS-permitted. Karma serves test assets from `http://localhost:<karma-port>`. A browser test hitting `http://127.0.0.1:14080` is a **cross-origin** request → needs `Access-Control-Allow-Origin: *` on the `http` container (one nginx `add_header` line — already in `./http/conf.d`).
- **Mixed content:** Karma serves over plain `http://`, so reaching `http://…:14080` is fine (no mixed-content block). Reaching the TLS container at `https://…:14443` from an `http://` Karma page is *not* blocked (https-from-http is allowed; only http-from-https is blocked). But the browser will reject the harness root CA unless it is in the OS trust store — and you cannot inject a CA into a CI headless Chrome cleanly. **Conclusion: browser targets validate only against the plain `http` container, only for WebSocket-shaped behavior, and skip all TLS/raw-TCP scenarios.** That is consistent with what the library actually supports in browsers.
- **CI mechanics:** the harness containers and the Karma browser run on the *same* GitHub Actions runner; `127.0.0.1:14080` resolves directly (Karma's Chrome is not itself containerized on `ubuntu-latest`). No `host.docker.internal` gymnastics needed on Linux CI.

### 3c. macOS / Apple reachability — Docker runs in a VM

On macOS, Docker Desktop runs the engine in a Linux VM. Containers are reachable from the host at `127.0.0.1:<published-port>` because Docker Desktop port-forwards published ports to the macOS host. K/N Apple tests run as native macOS/iOS-sim processes on the host → `127.0.0.1:14443` works **as long as the port is `published`** in compose (it is). iOS Simulator shares the host network stack, so `127.0.0.1` reaches the forwarded port too.

CA trust on Apple: Network.framework validates against the **system keychain**. For the *valid* TLS path to pass on Apple, the harness root CA must be added to the login keychain as trusted (`security add-trusted-cert`). This is a CI step (see §3d). For tests that use `SocketOptions.tlsInsecure()` no trust injection is needed.

Apple targets **only build on macOS CI** — the harness must therefore come up on the macOS runner too. Docker Desktop is preinstalled on GitHub `macos-*` runners *but is not started by default and is slow*; Colima is the lighter alternative. Recommendation: use **Colima** on macOS CI (`colima start`, then plain `docker compose up`). GitHub `macos-latest` is itself Apple Silicon (arm64), so Colima runs an arm64 Linux VM and the harness images build/run arm64-native — no QEMU.

**Apple `container` — considered, not adopted.** Apple's native containerization tool runs Linux containers in per-container lightweight VMs and is fast on Apple Silicon; it is attractive for *local development*. It is **not** the right choice for the harness *orchestrator*: (1) it is `container run`–oriented — multi-service `docker compose` orchestration, and especially container-to-container networking (our toxiproxy-fronts-backends topology), is immature; (2) running `container` on macOS while Linux CI runs Docker Compose **forks the harness runtime** — different networking semantics, different bugs — defeating the goal of a bit-identical stack everywhere; (3) it is unproven on GitHub `macos-*` runners, where Colima has a known `brew` + `colima start` path. The Apple-Silicon speed-up `container` would buy is instead obtained by building the images arm64-native under Colima (§3f). Revisit `container` later strictly as a local-dev convenience, never as a second CI orchestrator.

### 3d. CI: bringing the stack up/down

**Recommendation: Testcontainers on JVM/Android, raw `docker compose` (Gradle-managed) for K/N + JS.**

Reason: Testcontainers is a JVM library — it cannot manage containers for a `linuxX64` or `wasmJs` test process. A K/N test binary has no way to talk to the Docker daemon. So:

- **JVM & Android tests:** use Testcontainers (`org.testcontainers:testcontainers`). A JUnit `@BeforeClass` / a shared `ComposeContainer` starts the stack, and Testcontainers maps the dynamic ports. *But* — to keep the **shared `commonTest` assertions identical**, do not let Testcontainers pick random ports for the common path; pin the published ports in compose so JVM and K/N see the *same* numbers. Testcontainers is then used only for lifecycle (up/down/health), not port discovery.
- **K/N (Linux, Apple) & JS tests:** Gradle brings the stack up before the test task and down after:

```kotlin
// build.gradle.kts
val harnessUp by tasks.registering(Exec::class) {
    workingDir = file("test-harness")
    commandLine("docker", "compose", "up", "-d", "--wait")   // --wait blocks on healthchecks
}
val harnessDown by tasks.registering(Exec::class) {
    workingDir = file("test-harness")
    commandLine("docker", "compose", "down", "-v")
}
// gate every non-JVM real-network test task
listOf("linuxX64Test", "jsNodeTest", "macosArm64Test", "iosSimulatorArm64Test").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        dependsOn(harnessUp)
        finalizedBy(harnessDown)
    }
}
```

`--wait` (Compose v2.17+) blocks until every service's healthcheck passes — that eliminates the "container not ready yet" flake class entirely. Each service gets a `healthcheck:` (nginx: `curl -f localhost`; echo: a TCP probe; toxiproxy: `GET /version`).

CI gating: the harness is heavy. Keep the current split — fast PR check (`review.yaml`, **no harness**, runs only the deterministic in-process + pure-logic tests) and a harness-backed job. Convert `integration.yaml` (currently label-gated, hits the real internet) into the **harness** job: it brings the stack up and runs the conformance suite against `127.0.0.1`, so it can run on *every* PR, not just labeled ones, because it no longer depends on flaky public hosts.

### 3e. arm64 Linux — an existing target that bypasses Gradle

`linuxArm64` is a real target (`build.gradle.kts:504`; `src/linuxMain` is shared with `linuxX64`) and CI already runs it: `build-linux.yaml`'s `test-arm64` job on an `ubuntu-24.04-arm` runner. That job is structured unlike every other test task — it does **not** invoke Gradle. The x64 `build` job cross-compiles the K/N arm64 test binary and uploads it as an artifact; the arm64 runner downloads and executes the raw `.kexe` directly (`./build/bin/linuxArm64/debugTest/test.kexe`).

Consequence: the Gradle `dependsOn(harnessUp)` / `finalizedBy(harnessDown)` wiring from §3d **cannot** reach this job. The harness must be brought up at the workflow-step level instead:

```yaml
- name: Start harness
  working-directory: test-harness
  run: docker compose up -d --wait
- name: Run ARM64 tests (base module)
  run: ./build/bin/linuxArm64/debugTest/test.kexe
- name: Run ARM64 tests (socket-quic)
  run: ./socket-quic/build/bin/linuxArm64/debugTest/test.kexe
- name: Stop harness
  if: always()
  working-directory: test-harness
  run: docker compose down -v
```

This works cleanly because **`HarnessConfig` is compile-time** — it is generated from `harness.env` and compiled *into* the binary on the x64 build host. With `harness.env` static (`127.0.0.1` + the pinned ports §3a/§3d already mandate), the cross-compiled arm64 binary carries the correct endpoints; no runtime lookup. `harnessHost()` → `127.0.0.1` is correct because the harness and the binary share the arm64 runner. Docker is available on `ubuntu-24.04-arm` runners, and the images build arm64-native there (§3f).

### 3f. No emulation — an arch-matched runner matrix

Hard rule for a *determinism* harness: **never run the containers under QEMU emulation.** Emulation adds unpredictable scheduling latency — reintroducing exactly the timing jitter the harness exists to remove. The harness CI therefore runs as an arch-matched matrix:

| Test tasks | Runner | Harness arch |
|---|---|---|
| JVM, Android, JS, `linuxX64Test` | `ubuntu-24.04` | `linux/amd64` native |
| `linuxArm64Test` (raw `.kexe`, §3e) | `ubuntu-24.04-arm` | `linux/arm64` native |
| `macos*Test`, `ios*Test` | `macos-latest` (arm64) | `linux/arm64` under Colima |

Because §7.6 chose "build images per run," each runner builds the harness for its *own* architecture — so **no `buildx` cross-builds and no multi-arch registry are needed**. The only requirement on the Dockerfiles: pin base images that publish both `linux/amd64` and `linux/arm64` (nginx, toxiproxy, alpine, golang all do).

---

## 4. Consistent multiplatform API — one shared conformance suite

### 4a. Structure

Replace the scattered host-specific tests with a **parameterized conformance suite in `commonTest`**, driven by a sealed `Scenario` model:

```kotlin
// commonTest/.../harness/Scenario.kt
sealed interface Scenario {
    val host: String get() = harnessHost()       // the one expect/actual
    val port: Int

    data object PlainEcho        : Scenario { override val port = HarnessConfig.echoPort }
    data object Http             : Scenario { override val port = HarnessConfig.httpPort }
    data object TlsValid         : Scenario { override val port = HarnessConfig.tlsValidPort }
    data object TlsSelfSigned    : Scenario { override val port = HarnessConfig.tlsSelfSignedPort }
    data object TlsExpired       : Scenario { override val port = HarnessConfig.tlsExpiredPort }
    data object TlsWrongHost     : Scenario { override val port = HarnessConfig.tlsWrongHostPort }
    data object TlsUntrustedRoot : Scenario { override val port = HarnessConfig.tlsUntrustedPort }
    // L4 faults:
    data object PeerCloseImmediate : Scenario { override val port = HarnessConfig.toxiEchoPort }
    // ...
}
```

The conformance tests iterate scenarios and assert identical behavior. Each test still honors `getNetworkCapabilities()` — browser/wasmJs skip socket scenarios, exactly as today's `runTestNoTimeSkipping` already does.

```kotlin
// commonTest/.../ConformanceTests.kt
class TlsConformanceTests {
    @Test fun validCert_handshakeSucceeds() = harnessTest(Scenario.TlsValid) { socket ->
        assertTrue(socket.isOpen())
        socket.writeString(getRequest("/"))
        assertHttpResponse(socket)          // shared helper, lenient UTF-8 (see §5)
    }
    @Test fun selfSigned_rejectedByDefault()  = harnessExpectFailure(Scenario.TlsSelfSigned)
    @Test fun expired_rejectedByDefault()     = harnessExpectFailure(Scenario.TlsExpired)
    @Test fun wrongHost_rejectedByDefault()   = harnessExpectFailure(Scenario.TlsWrongHost)
    @Test fun selfSigned_acceptedWhenInsecure() =
        harnessTest(Scenario.TlsSelfSigned, SocketOptions.tlsInsecure()) { assertTrue(it.isOpen()) }
}
```

`harnessTest` is a single `commonTest` helper wrapping `runTestNoTimeSkipping` + connect + `WEBSOCKETS_ONLY` skip + cleanup. The whole `TlsErrorTests` / `NetworkIntegrationTests` / `SniStrictHostsTest` collapse into this.

### 4b. Tests that should MOVE to `commonTest`

| Currently in | Should move to | Why |
|---|---|---|
| `SniStrictHostsTest` (`commonJvmTest`) | `commonTest` | SNI is a cross-platform contract; with the multi-vhost `tls` container every platform can run it |
| `LinuxTlsTests.tlsConnectionRequiresCACertificates` (`linuxTest`) | `commonTest` (the valid-cert conformance test) | "TLS handshake against a valid cert works" is not Linux-specific |
| `JvmExceptionSubtypeTests.brokenPipeOrReset_*`, `readAfterPeerClose_*` | keep JVM-strict subtype assertion in `commonJvmTest`, but the **scenario** (peer-close via L4) moves to a shared `harness` helper both use | de-dup the toxiproxy setup |
| `IoUringCancelTests` cancellation *intent* | the cancellation-correctness assertions belong in a `commonTest` `CancellationConformanceTests`; only the io_uring-specific syscall-count check stays in `linuxTest` | cancellation is a cross-platform contract |

Tests that should **stay platform-specific** (correctly so): `AppleExceptionMappingTests`, `WrapJvmExceptionTests`, `WrapNodeErrorMappingTests`, `LinuxExceptionMappingTests`, `LinuxQuicAddressMarshallingTest`, `IoUringManagerTests` (io_uring is Linux-only), `NodeBufferPoolTests` — these test platform-internal mapping/marshalling, not observable socket behavior.

---

## 5. Timing flakes — concrete per-test fixes

No container fixes a wall-clock assertion. The principle: **assert observable state, not duration.** Cancellation correctness means "the operation returned promptly *relative to the work it was avoiding*" — assert that the job is `isCompleted` after `join()` returned, and that you did **not** wait for the 30s read timeout. Use a generous *watchdog* (`withTimeout`) as the real bound; the `< 500ms` micro-budget is the bug.

| Test | Current | Fix |
|---|---|---|
| `cancelReadCompletesQuickly` | `assertTrue(elapsed < 500)` | Wrap the `readJob.cancel(); readJob.join()` in `withTimeout(5.seconds)`. If `join()` returns, cancellation worked. Assert `readJob.isCancelled`. The *real* regression (waiting the full 30s read timeout) is caught because the 5s watchdog throws. Optionally keep a **soft** check: `if (elapsed > 2000) println("WARN slow cancel ${elapsed}ms")` — diagnostic, non-failing. |
| `cancelWriteCompletesQuickly` | `elapsed < 500` | same pattern; `withTimeout(5.seconds)` watchdog + `assertTrue(writeJob.isCancelled)`. |
| `multipleCancellationsDontCrash` | `elapsed < 500` ×3 | drop the budget entirely — the test name says the intent is "don't crash / don't leak". Keep the loop, assert no exception escaped + `client` closed cleanly. Watchdog `withTimeout(10.seconds)` for the whole loop. |
| `ServerCancellationTests.serverClosesQuickly` | `elapsed < 500` | `withTimeout(5.seconds) { server.close(); serverJob.join() }` then `assertFalse(server.isListening())`. |
| `IoUringCancelTests.cancelCompletesQuickly` | `< 200ms` | `withTimeout(5.seconds)` watchdog; assert job cancelled. 200ms is meaningless under WSL2 scheduling jitter. |
| `IoUringCancelTests.multipleCancelCyclesNoLeaks` | `< 500ms` ×5 | the intent is *no leak* — pair it with an fd-count / `readStats` check before & after, drop the per-cycle budget. |
| `IoUringManagerTests.shutdownCompletesQuickly` | `< 500ms` | the real assertion: "NOP wakeup fired so we did NOT wait the 1s poll timeout". Make `IoUringManager` expose a test hook (e.g. `lastShutdownWokenByNop: Boolean`) and assert *that*. If a hook is too invasive: `withTimeout(3.seconds)` watchdog (still well under the 1s×N degenerate case you're guarding against — actually use a tighter bound only if you can prove the CI floor; otherwise 3s). |
| `IoUringManagerTests.cleanupAndReinitializeWorks` | `< 500ms` ×3 | functional reuse is the intent — the `response.contains(testMessage)` assert already proves it. Delete the `cleanupTime` assert. |
| `IoUringManagerTests.cleanupCancelsPendingOperations` | `< 500ms` | `readException != null || !client.isOpen()` is the real assertion and it is good. Delete the `cleanupTime` budget; keep a `withTimeout` watchdog. |
| `IoUringManagerTests.rapidCleanupReinitializeCycles` | `< 500ms` ×10 | delete budget; the fact that 10 cycles complete inside the outer `runTestNoTimeSkipping(30s)` is enough. |
| `IoUringManagerTests.rapidOpenCloseCycles` | `successCount >= 80%` | under WSL2 even 80% can flake. Make it deterministic: server must `accept` every connection (raise backlog), and assert **100%** success with a per-connection `withTimeout`. A ratio assertion hides real bugs. |
| `ResourceCleanupTests.coroutineCancellationCleansUpSocket` | Run-2 failure: outer `withTimeout(30s)` expired | The body has `delay(60000)` on the *server* side and a client `read(60.seconds)`. The 30s `runTestNoTimeSkipping` budget is *smaller* than the 60s delays — if the cancel timing slips, the watchdog fires. Fix: drop the server `delay(60000)` to `delay(5.seconds)`, drop client `read(60.seconds)` to `read(5.seconds)`; the test cancels long before either fires, so shorter values change nothing functionally but make the 30s outer budget comfortably large. |
| `SimpleSocketTests.checkPort()` CLOSE_WAIT | single-shot `lsof` assert (Run-1 failure) | TCP teardown is asynchronous. Replace the one-shot assert with a **poll-with-timeout**: `withTimeout(5.seconds) { while (readStats(port,"CLOSE_WAIT").isNotEmpty()) delay(50) }`. A socket that never drains is a real leak (test fails); a socket that drains in 200ms is normal kernel behavior (test passes). This single change kills the most-cited flake. |
| `tlsWithSni` UTF-8 decode | `readString()` strict-decodes a TCP-fragmented response | The bug: a multi-byte UTF-8 char split across two `read()` calls makes strict decoding throw. Fix in the **test helper**: read the full HTTP response into one buffer (loop until `Connection: close` EOF) *then* decode once; or assert on the byte-level status line (`HTTP/`) without full-body string decoding. Add a dedicated `harness` test that *deliberately* fragments a multi-byte payload via a toxiproxy `slicer` toxic — that turns this latent bug into a deterministic regression test. |

**Wrapper bug to file (not a flake — a real defect surfaced by the flake):** `ExceptionIntegrationTests.writeAfterPeerClose` and `JvmExceptionSubtypeTests.brokenPipeOrReset` saw a **raw** `java.io.IOException: Broken pipe` from `SocketDispatcher.write0` escape unwrapped. Even with a deterministic L4 close, the JVM write path must wrap that `IOException` into `SocketClosedException.BrokenPipe`. The harness makes the scenario reproducible; the fix is in `commonJvmMain`'s write error mapping. Track separately.

---

## 6. Migration path — phased, suite stays green throughout

**Phase 0 — stop the bleeding (no harness, ~1 day). ✅ DONE — commit `5421d42`.** Pure assertion fixes, mergeable immediately:
- ✅ Rewrote all `elapsed < Nms` assertions per §5 (watchdog + state).
- ✅ Converted `checkPort()` to poll-with-timeout.
- ✅ Fixed `coroutineCancellationCleansUpSocket` delay values.
- ✅ Fixed the `tlsWithSni` decode (and `SniStrictHostsTest`, found failing during verification — same UTF-8 straddle) to check the ASCII status line from raw bytes.
- Result: the timing/CLOSE_WAIT/decode flake classes are gone before the harness exists. Verified `jvmTest` 224 green, `linuxX64Test` green. Note: `tlsWithSni`/`SniStrictHostsTest` still reach the public internet — that dependency is removed in Phase 2; the broader public-host TLS tests share the latent decode fragility until then.

**Phase 1 — harness skeleton. ✅ DONE — commit `e3569a5`.** `test-harness/` with `echo` + `http` containers, `docker-compose.yml` (healthchecks on `127.0.0.1` — `localhost` resolves to `::1` and bites), `harness.env`, `generateHarnessConfig` Gradle task generating a single `HarnessConfig` object onto `commonTest`, the single `expect fun harnessHost()` + 5 actuals, `isHarnessAvailable()` probe for graceful skip, `harnessUp`/`harnessDown` Exec tasks (no-op on Windows / missing Docker / `HARNESS_DISABLED=true`) wired to `jvmTest` + `linuxX64Test`. `HarnessConformanceTests` covers the L0 TCP echo round-trip and the L0 HTTP status-line. Verified on JVM and `linuxX64`. The public-host `httpRawSocket*` / `NetworkIntegrationTests.tcp_*` migrations land as part of the Phase-2 work (the harness exists; the tests just need to be rewritten against it).

**Phase 2 — TLS cert matrix (L6). ✅ INFRASTRUCTURE + partial migration — commits `9d55cd3`, `0450fd0`.**
- ✅ `tls` container, `gen-certs.sh` (harness-root + untrusted CA + 5 leaf certs, `openssl ca` backdating for the expired cert), 5 nginx server-blocks on 5 ports, `generateHarnessCerts` Gradle task wired before `harnessUp`, 5 new ports exposed via `HarnessConfig`.
- ✅ `TlsConformanceTests` (10 tests): valid-handshake + valid-GET smoke, four cert-rejection scenarios with strict `assertFailsWith<SSLSocketException>`, four insecure-mode acceptance scenarios. Verified on both JVM and `linuxX64` (BoringSSL) — multiplatform TLS error mapping is now provable.
- ✅ Migrated the six cert-validation tests in `TlsErrorTests` (`badssl.com` family) to `@Ignore` with pointer comments to their harness equivalents.
- 🔶 **CA-trust CI injection (§3c/§3d/§7.3) deferred** — the valid-path test uses `tlsInsecure()` for now; once CI injects the harness root CA into JVM `cacerts` / Linux CA bundle / Apple keychain, switch it to `tlsDefault()`. Tracked in `TODO.md`.
- 🔶 **Remaining migrations deferred** — `NetworkIntegrationTests.tls_*`, `SniStrictHostsTest`, `LinuxTlsTests`, plus the read-shape / concurrent-connection variants in `TlsErrorTests`. All have direct harness equivalents now; sweep is mechanical, tracked in `TODO.md`.

**Phase 3 — toxiproxy (L4).** Add the `toxiproxy` service. Build the shared `harness` toxic-setup helper. Migrate `ExceptionIntegrationTests` peer-close tests + `JvmExceptionSubtypeTests` scenarios onto deterministic toxiproxy faults. Add the partial-read / UTF-8-straddle regression test. File the broken-pipe wrapper bug.

**Phase 4 — netem (L3) + QUIC.** Static netem profiles for deterministic connect-timeout (replace the `10.255.255.1` magic IP). Add the `quic-echo` container (wraps existing `QuicEchoTestServer`); migrate `QuicIntegrationTests` off `cloudflare-quic.com`. Address the `TODO.md` gaps: multi-address / IPv6-only DNS — the harness can serve a name with both an A and an unreachable AAAA record (via a tiny DNS container or `/etc/hosts` tricks) to exercise the Linux addrinfo fallback in-repo.

**Phase 5 — browser/wasmJs + cleanup.** Confirm browser/wasmJs runs stay green by *skipping* socket scenarios (they already do via `WEBSOCKETS_ONLY`). Add CORS header to the `http` container for any future browser-reachable test. Delete the now-dead public-host tests and the `integration.yaml` internet dependency; fold the harness job into `review.yaml` so every PR gets deterministic network coverage.

**Green-throughout rule:** at each phase, the new harness test lands and passes *before* the corresponding public-host test is deleted. Never delete-then-add.

---

## 7. Resolved decisions

Settled with the user (2026-05-22):

1. **CI Docker on macOS — Colima.** `macos-*` runners use Colima so Apple targets exercise the full harness; no public-host fallback for Apple.
2. **netem control — static profiles.** Fixed faulty-port profiles; no test currently needs to change conditions mid-connection. Revisit only if a dynamic scenario appears.
3. **CA trust injection — inject per platform in CI.** A CI step adds the harness root CA to each trust store (Apple keychain, Linux CA bundle for BoringSSL, JVM `cacerts`) so the *valid* TLS path keeps real default-path coverage.
4. **Browser scope — confirmed skip.** Browser/wasmJs targets do not exercise the socket harness; the existing `WEBSOCKETS_ONLY` skip stands.
5. **`integration.yaml` — fold into `review.yaml`.** The harness runs as an always-on job on every PR; the label-gated public-internet job is removed.
6. **Harness location — in-repo `test-harness/`.** Committed into this repo for the simplest `docker compose` task wiring; CI builds images per run.
7. **Apple `container` — not adopted; Colima stands.** Apple's native containerization tool is fast on Apple Silicon but immature for multi-service compose + container-to-container networking, and adopting it would fork the harness runtime from Linux CI. macOS CI uses Colima; the Apple-Silicon speed-up comes from arm64-native images, not a second runtime. See §3c.
8. **arm64 — first-class, no emulation.** `linuxArm64` is an existing target with an existing `ubuntu-24.04-arm` CI job that runs the raw `.kexe` (not Gradle). The harness CI runs as an arch-matched matrix (`ubuntu-24.04` / `ubuntu-24.04-arm` / `macos-latest`); each runner builds the harness for its own arch — no QEMU, no `buildx`. The arm64 job brings the stack up/down at the workflow-step level since it bypasses Gradle. See §3e, §3f.
