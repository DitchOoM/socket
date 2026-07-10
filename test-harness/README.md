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
| `toxiproxy` | `8474` (API), `15000/15080/15443` (proxies) | L4 fault injection in front of echo/http/tls. |
| `netem-blackhole` | `172.30.0.99:14999` (bridge IP, not published) | Accepts SYNs, drops all egress — deterministic connect-timeout. |
| `rst`   | `14998` | Deterministic peer-close sidecar (SO_LINGER=0 + close after 1 byte). |
| `quic-echo` | `14433/udp` | JVM QUIC echo server (quiche). |
| `controller` | `14100` | **W6 control plane** — serves the scenario manifest (`GET /describe`) + `GET /health` for `withNetworkHarness`. |

## Run it

```bash
# Build the two JVM image inputs first (harnessUp does both automatically):
./gradlew :socket-quic-quiche:quicEchoJar :socket-testsuite:controllerJar

cd test-harness
docker compose up -d --wait
# … run tests …
docker compose down -v
```

Gradle does this automatically: `./gradlew jvmTest` (and `linuxX64Test`)
calls `harnessUp` before the test task and `harnessDown` after. If Docker
isn't installed those tasks no-op (tests then skip the harness-backed
cases at runtime via `isHarnessAvailable()` / `withNetworkHarness`).

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
    "toxiproxy":       {"api":8474,"echo":15000,"http":15080,"tls":15443},
    "rst":             {"host":"127.0.0.1","port":14998},
    "blackhole":       {"host":"172.30.0.99","port":14999},
    "quic-echo":       {"host":"127.0.0.1","port":14433}
} }
```

A scenario missing from the manifest is unavailable on that harness runtime
(e.g. netem under Apple `container`) — consumers probe with
`scenarioOrNull`, never hard-code. Every response carries
`Access-Control-Allow-Origin: *` so future browser targets can fetch it.

### Consumer quickstart

The harness runs from a single downloadable compose file — no repo checkout.
Every release publishes multi-arch (`linux/amd64` + `linux/arm64`) images to
GHCR (`ghcr.io/ditchoom/socket-test-harness-{echo,rst,quic-echo,controller}`,
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
└── controller/             # W6 control plane (jar from :socket-testsuite:controllerJar)
```

The four services with local build contexts (echo, rst, quic-echo,
controller) are published to GHCR by the release flow (`merged.yaml` →
`publish-harness-images`, multi-arch via buildx/qemu) as
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
