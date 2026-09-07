# test-harness/

Local, deterministic replacement for the public-internet hosts the test
suite used to depend on (`example.com`, `cloudflare.com`, `httpbin`,
`badssl.com`, …).

See `../TESTING_STRATEGY.md` for the full design. This directory is
**Phase 1** of that plan — just the L0 services:

| Service | Port (127.0.0.1) | What it is |
|---|---|---|
| `echo`  | `14000` | socat-backed TCP echo. Used by raw-socket tests and as the cheapest availability probe. |
| `http`  | `14080` | nginx. Routes: `/` (HTML), `/get` (plain-text `ok`), `/json`, `/large` (>1 KB). CORS-permissive. |
| `tls`   | `14443`–`14493` | nginx cert matrix — one vhost per scenario (valid / self-signed / expired / wrong-host / untrusted-root / TLS 1.3-only). |
| `toxiproxy` | `8474` (API), `15000/15080/15443` (root-test proxies), `15900` (`suite-echo`, withNetworkHarness) | L4 fault injection in front of echo/http/tls. The testsuite's `suite-echo` proxy is name- and port-isolated from the root module's proxies because their test tasks run in parallel. |
| `netem-blackhole` | `172.30.0.99:14999` (bridge IP, not published) | Answers ARP, accepts SYNs, drops its own SYN-ACKs (egress TCP from 14999) — deterministic connect-timeout. |
| `rst`   | `14998` | Deterministic peer-close sidecar (SO_LINGER=0 + close after 1 byte). |
| `quic-echo` | `14433/udp` | JVM QUIC echo server (quiche). |
| `udp-echo` | `14434/udp` | socat-backed UDP datagram echo (`UDP-RECVFROM,fork`). Datagram analogue of `echo`; the upstream `udp-toxi` forwards to. |
| `udp-toxi` | `8475` (control API, TCP), `14435/udp` (`suite-udp` relay data plane) | L4 UDP/QUIC fault plane (toxiproxy is TCP-only). HTTP/REST control plane provisions a `FaultSchedule` per named relay; a per-datagram UDP data plane impairs+forwards. JVM sidecar driving the same testkit `ImpairmentEngine` as the Tier-A pipe (A⇄C parity). The `suite-udp` relay is name- and port-isolated for `withNetworkHarness`'s `impairedUdp`. |
| `controller` | `14100` | **W6 control plane** — serves the scenario manifest (`GET /describe`) + `GET /health` for `withNetworkHarness`. |

## Run it

```bash
# Build the JVM image inputs first (harnessUp does all of them automatically).
# ⚠️ On a NON-Linux host, `quicEchoJar` stages that host's natives and the container cannot load
# them — use `fetchQuicEchoContext` instead, as harnessUp does. See "On macOS" below.
./gradlew :socket-quic-quiche:quicEchoJar :socket-testsuite:controllerJar :socket-testsuite:udpToxiJar

cd test-harness
docker compose up -d --wait
# … run tests …
docker compose down -v
```

Gradle does this automatically: `./gradlew jvmTest` (and `linuxX64Test`)
calls `harnessUp` before the test task and `harnessDown` after. If Docker
isn't installed those tasks no-op (tests then skip the harness-backed
cases at runtime via `isHarnessAvailable()` / `withNetworkHarness`).

## On macOS (and any non-Linux host)

Two separate things used to stop this working. Both are handled, but the second needs a choice from
you.

**The image no longer depends on who built it.** `quicEchoJar` stages *the host's* quiche natives
(`prepareQuicheNativeLib` is "for the current host OS/arch") and the container is Linux, so a Mac-built
jar made the container die on `META-INF/native/linux-arm64/libquiche.so (not on classpath)`. `harnessUp`
now fetches CI's `quic-echo-docker-context` on non-Linux hosts — the same artefact the integration lanes
consume, carrying both Linux arches. Linux hosts still build locally.

⚠️ That is **`main`'s** server, not your working tree's. Better for client-side work (a known-good
peer); useless if you are changing `QuicEchoTestServer` itself — build on Linux for that.

**A Lima-backed docker context does not publish UDP to the macOS host.** Measured on `colima`
(`docker context ls` → `colima *`): `docker compose ps` reports `127.0.0.1:14433->14433/udp` and
`docker port` agrees, while `lsof -iUDP:14433` shows no socket and datagrams never arrive. Reproduced
minimally with `docker run -p 127.0.0.1:19998:19998/udp alpine/socat` — this is Lima's UDP
port-forwarding gap, not something about QUIC or this repo.

⚠️ Attribute it to your **active context**, not to "Docker on macOS". Docker Desktop is a different
runtime and is not what these measurements were taken against; the TCP suites are wired to run on
developer Macs, so do not assume TCP is affected either without measuring it.

Apple's `container` avoids the question entirely — it gives the container its **own routable IP**, so
there is no publishing proxy in the path:

```bash
# The context needs the jar and certs, which harnessUp's dependencies produce:
./gradlew fetchQuicEchoContext generateHarnessCerts     # non-Linux; on Linux use :socket-quic-quiche:quicEchoJar

container build -t quic-echo-local test-harness/quic-echo/
container run -d --name quicecho quic-echo-local        # no args: the image's CMD is complete
container ls                                            # read the IP, e.g. 192.168.64.11

sed -i '' 's/^HARNESS_HOST=.*/HARNESS_HOST=192.168.64.11/' test-harness/harness.env
./gradlew :socket-quic-quiche:jvmTest --tests '*QuicHarnessIntegrationTests*' --rerun -x harnessUp -x harnessDown

container rm -f quicecho && git checkout test-harness/harness.env
```

Measured on macOS arm64: `QuicHarnessIntegrationTests` **7 OK / 0 SKIP in 8s**, against 1m40s of
timeouts on the colima context.

⚠️ Filter to `QuicHarnessIntegrationTests`, **not** `*Harness*`. The broader pattern also selects
`QuicImpairedHarnessTests`, which needs the controller and udp-toxi sidecars that this single-container
recipe does not run — those would report green having executed nothing, which is the lane-vacancy shape
this repository has been bitten by before.
⚠️ `container run <image> <args>` **replaces** the CMD; the entrypoint then tries to exec the cert path
(`/app/cert.crt: Permission denied`). Pass no args.
⚠️ `container` runs one container, not a compose stack.
⚠️ These containers have **no IPv6 interface**, so a v6 axis cannot be exercised on this path.
⚠️ `-x harnessUp -x harnessDown` is there because `jvmTest` *dependsOn* `harnessUp` and is *finalizedBy*
`harnessDown`, so a Gradle run would otherwise spend ~1m40s standing up the compose stack you are
deliberately not using. (`harnessDown` runs `docker compose down -v`, which cannot touch an Apple
`container` — the container survives; the time does not.)

## The controller & `GET /describe` (W6 control plane)

The `controller` service is the consumer-facing entry point to the harness
(RFC_DETERMINISTIC_SIMULATION §7): a small JVM HTTP server (hand-rolled
HTTP/1.1 over the library's own `ServerSocket`; jar built by
`:socket-testsuite:controllerJar`). It reflects `harness.env` (passed in via
compose `env_file`) as a JSON manifest, so consumers never read that file:

- `GET /health` → `200 ok`
- `GET /describe` → `200` JSON manifest (version 1):

```json
{ "version": 1, "scenarios": {
    "echo":            {"host":"127.0.0.1","port":14000},
    "http":            {"host":"127.0.0.1","port":14080},
    "tls-valid":       {"host":"127.0.0.1","port":14443},
    "tls-self-signed": {"host":"127.0.0.1","port":14453},
    "tls-expired":     {"host":"127.0.0.1","port":14463},
    "tls-wrong-host":  {"host":"127.0.0.1","port":14473},
    "tls-untrusted":   {"host":"127.0.0.1","port":14483},
    "tls13-only":      {"host":"127.0.0.1","port":14493},
    "toxiproxy":       {"api":8474,"echo":15900,"http":15080,"tls":15443},
    "rst":             {"host":"127.0.0.1","port":14998},
    "blackhole":       {"host":"172.30.0.99","port":14999},
    "quic-echo":       {"host":"127.0.0.1","port":14433},
    "udp-echo":        {"host":"127.0.0.1","port":14434},
    "udp-toxi":        {"api":8475,"data":14435}
} }
```

A scenario missing from the manifest is unavailable on that harness runtime
(e.g. netem under Apple `container`) — consumers probe with
`scenarioOrNull`, never hard-code. Every response carries
`Access-Control-Allow-Origin: *` so future browser targets can fetch it.

### Consumer quickstart

The harness runs from a single downloadable compose file — no repo checkout.
Every release publishes multi-arch (`linux/amd64` + `linux/arm64`) images to
GHCR (`ghcr.io/ditchoom/socket-test-harness-{echo,rst,quic-echo,controller,udp-echo,udp-toxi}`,
tagged `<version>` + `latest`); `harness-consumer.yml` wires them together
with the stock nginx/toxiproxy images on the same pinned ports:

```bash
curl -LO https://raw.githubusercontent.com/DitchOoM/socket/main/test-harness/harness-consumer.yml
docker compose -f harness-consumer.yml up -d --wait
# … run your tests …
docker compose -f harness-consumer.yml down -v
```

(Compose >= 2.23.1. The TLS cert-matrix service is repo-only — its certs are
generated locally by `tls/gen-certs.sh` — so `tls(...)` scenarios need the
in-repo stack; everything else, including toxiproxy impairment, works from
the standalone file. Pin the image tags to your socket version for
reproducible CI.)

Then add `com.ditchoom:socket-testsuite` (published to Maven Central with
the rest of the stack) to your `commonTest` dependencies and write plain
multiplatform test code (JVM/Android/Linux/Apple today; browser targets are
future work):

```kotlin
kotlin.sourceSets.commonTest.dependencies {
    implementation("com.ditchoom:socket-testsuite:<version>")
}
```

```kotlin
import com.ditchoom.socket.testsuite.harness.*

@Test
fun myHarnessTest() = runQuicTest {
    withNetworkHarness {                    // skips cleanly when the stack is down
        val echo: HarnessEndpoint = echo()  // {host, port} from /describe
        val expired = tls(TlsScenario.EXPIRED)

        impaired(latency = 200.milliseconds) { proxy ->
            // proxy = toxiproxy-fronted echo with +200 ms downstream latency
        }
        peerReset { proxy -> /* next packet through the proxy draws an RST */ }
        blackhole { ep -> /* SYN accepted, all egress dropped: connect times out */ }
    }
}
```

`withNetworkHarness` returns `false` (and prints one loud line) instead of
failing when the controller is unreachable — the harness being down must
never break a consumer's suite.

## Layout

```
test-harness/
├── harness.env             # source of truth for ports — generates HarnessConfig.kt,
│                           #   and is env_file-injected into the controller
├── docker-compose.yml      # the stack (in-repo dev/CI: build: contexts)
├── harness-consumer.yml    # W7 standalone consumer stack (GHCR images, no checkout)
├── echo/Dockerfile         # alpine + socat
├── http/conf.d/default.conf# nginx routes
├── tls/                    # cert matrix (gen-certs.sh; certs gitignored)
├── rst/                    # peer-close sidecar
├── quic-echo/              # QUIC echo image (jar from :socket-quic-quiche:quicEchoJar)
├── udp-echo/               # UDP datagram echo (alpine + socat)
├── udp-toxi/               # UDP/QUIC fault relay (jar from :socket-testsuite:udpToxiJar)
└── controller/             # W6 control plane (jar from :socket-testsuite:controllerJar)
```

The six services with local build contexts (echo, rst, quic-echo,
controller, udp-echo, udp-toxi) are published to GHCR by the release flow
(`merged.yaml` → `publish-harness-images`, multi-arch via buildx/qemu) as
`ghcr.io/ditchoom/socket-test-harness-<name>:{<version>,latest}` —
`harness-consumer.yml` consumes those.

Add a port to `harness.env`, run `./gradlew generateHarnessConfig`, and
the generated `HarnessConfig` object exposes it to every test source set.
No `expect/actual` to maintain. The controller picks the same value up from
its environment, so the `/describe` manifest stays in lockstep.

## Future phases

- `POST /capture/start|stop` on the controller (tcpdump/qlog bundles, §5).
- Browser (`fetch`-based) control transport for `withNetworkHarness`.
- A `tls` path for the standalone consumer stack (certs are generated
  locally today, so the cert-matrix scenarios remain repo-only).

W7 (RFC_DETERMINISTIC_SIMULATION §8) shipped: GHCR harness images on every
release, `socket-testsuite` published to Central + wired into the
`validate-artifacts.yaml` loop, and a `withNetworkHarness` consumer smoke in
`.ci/consumer-smoke`.
