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

## Run it

```bash
cd test-harness
docker compose up -d --wait
# … run tests …
docker compose down -v
```

Gradle does this automatically: `./gradlew jvmTest` (and `linuxX64Test`)
calls `harnessUp` before the test task and `harnessDown` after. If Docker
isn't installed those tasks no-op (tests then skip the harness-backed
cases at runtime via `isHarnessAvailable()`).

## Layout

```
test-harness/
├── harness.env             # source of truth for ports — generates HarnessConfig.kt
├── docker-compose.yml      # the stack
├── echo/Dockerfile         # alpine + socat
├── http/conf.d/default.conf# nginx routes
└── http/www/index.html     # static HTML for GET /
```

Add a port to `harness.env`, run `./gradlew generateHarnessConfig`, and
the generated `HarnessConfig` object exposes it to every test source set.
No `expect/actual` to maintain.

## Future phases

- **Phase 2** — `tls` service (cert matrix) + per-platform CA injection.
- **Phase 3** — `toxiproxy` service for L4 fault injection.
- **Phase 4** — netem (L3) and `quic-echo` (UDP).
