# socket — open follow-ups

Tracked here so the gaps from each session aren't lost.

## Test-harness migration — closed (2026-05-23)

Phases 0–5 of `TESTING_STRATEGY.md` are done. The work that landed:

- Phase 0 — flake reduction (commit `5421d42`).
- Phase 1 — harness skeleton: `test-harness/`, `generateHarnessConfig`, `HarnessConformanceTests` (`e3569a5`, `f51b719`).
- Phase 2 — TLS cert matrix: 5 certs × 2 scenarios = 10 conformance tests; cert-rejection family migrated (`9d55cd3`, `0450fd0`, `c4f4889`).
- Phase 3 — toxiproxy / L4 fault injection: `Toxiproxy` helper, deterministic broken-pipe regression, partial-read slicer test, `TlsErrorTests` sweep (`0bff529`, `3f9c917`, `a0d3962`).
- Phase 4 — netem + TLS-1.3 + QUIC: `netem-blackhole`, sixth nginx vhost, `quic-echo` container + `:socket-quic:quicEchoJar`, `QuicHarnessIntegrationTests`, `SimpleSocketTests`/`TlsValidPathConformanceTests` migrations (`d520030`, `3ea5887`).
- Phase 5 — CI fold-in + tlsDefault() switch: arch matrix in `review.yaml`, per-platform CA injection, `integration.yaml` deleted, `jsNodeTest` wired, valid-path tests on `tlsDefault()` (`7c44876`, `20313af`).

See `TESTING_STRATEGY.md` §6 for the full per-phase summary.

### Carried out of migration scope

- [ ] **Subtype-precise `SocketClosedException` cross-platform regression.** The three `ExceptionConformanceTests` cases assert the sealed *parent* class only because toxiproxy 2.12's `reset_peer` toxic produces either EPIPE (BrokenPipe), ECONNRESET (ConnectionReset), or graceful EOF (EndOfStream) depending on kernel scheduling — the production wrappers map each errno correctly, but we can't deterministically *trigger* a specific errno via toxiproxy. A subtype-precise regression test would need a sidecar TCP server with `SO_LINGER=0` and direct `close()` (no graceful shutdown) so the RST path is guaranteed. Cheap to add — maybe one new container in `test-harness/` (a tiny socat/go binary with the right socket options) — but out of scope for this migration.
- [ ] **Delete the `@Ignore`'d public-host originals throughout `TlsErrorTests`, `ExceptionIntegrationTests`, `JvmExceptionSubtypeTests`, `QuicIntegrationTests`, etc.** Kept in place under the green-throughout rule (TESTING_STRATEGY.md §6) until Apple CI proves the harness equivalents at least once on `macos-latest`. Sweep with a follow-up PR after the first green `review.yaml` run on this branch.
- [ ] **`SniStrictHostsTest` migration.** Needs separate `server_name`-only nginx vhosts (no `default_server` fallback) on port 443 to exercise SNI strictness. The `tls` container's `valid` block already serves `valid.test` + `localhost` as aliases; an additional pair of strict vhosts will let the migration land. Out of scope for this PR.
- [ ] **IPv6-only / multi-address DNS coverage** (`LinuxClientSocket` addrinfo iteration). Deferred during Phase 4: `LinuxClientSocket.open()` builds the addrinfo struct inside `memScoped` cinterop with no injection seam, and a real dnsmasq fixture would need root or container-side test execution. Ships when either (a) `LinuxClientSocket` grows a testable injection point, or (b) container-side test execution lands.
- [ ] **Full RFC 8305 Happy Eyeballs racing.** Current Linux addrinfo iteration is sequential — if the first IPv6 address SYNs out, the user waits ~75 s for the v4 fallback. Real Happy Eyeballs races A and AAAA in parallel with a small "resolution preference" delay. Not blocking, latency-sensitive future work.
- [ ] **Cross-platform parity test for addrinfo iteration.** JVM (`AsynchronousSocketChannel`) and Apple (`NWConnection`) iterate addresses internally; a unit test on each platform that documents the behavior would be useful once an injection seam exists.

## Other (pre-existing)

Carried from earlier sessions / memories. Not introduced by the harness migration.

- [ ] socket-quic pool ownership audit — see [[socket_quic_recvbufpool_bug]] for the regression that started this thread; the fix landed in commit `80575c1` but the pool-sharing contract isn't formally documented.
