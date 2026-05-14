# socket — open follow-ups

Tracked here so the gaps from today's session (2026-05-14) aren't lost.

## LinuxClientSocket addrinfo fallback (`4073f29`)

Today's commit walks the addrinfo linked list sequentially when the head address fails. This unblocks dual-stack hosts whose AAAA records are unreachable (the canonical reproducer was `broker.hivemq.com:8884`). Open items:

- [ ] **In-repo unit test for the iteration logic.** Currently the fix is only validated end-to-end via `websocket :linuxX64Test --tests "*PublicWssValidationTest.hivemqWssConnect"`. A `:socket:linuxX64Test` that targets a host known to return multi-address records (or a fixture that stubs `getaddrinfo`) would catch regressions inside this repo.
- [ ] **IPv6-only host coverage.** No regression test confirms that an IPv6-only host (no A record) still connects on the first AAAA.
- [ ] **Full RFC 8305 Happy Eyeballs racing.** Current change is sequential only — if the first IPv6 address takes the full TCP SYN timeout to fail, the user waits ~75 s before the IPv4 fallback is tried. Real Happy Eyeballs races A and AAAA in parallel with a small "resolution preference" delay. Not blocking, but worth doing for latency-sensitive use cases.
- [ ] **Cross-platform parity.** JVM (`AsynchronousSocketChannel`) and Apple (`NWConnection`) already iterate addresses internally via their platform abstractions. Worth confirming with a unit test on each platform that the documented behavior matches Linux's new fallback semantics.

## Other (pre-existing)

Carried from earlier sessions / memories. Not introduced today.

- [ ] socket-quic pool ownership audit — see [[socket_quic_recvbufpool_bug]] for the regression that started this thread; the fix landed in commit `80575c1` but the pool-sharing contract isn't formally documented.
