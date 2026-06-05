# Handoff — index

This root handoff is a pointer. The per-effort handoffs hold the live detail.

## Active work

- **HTTP/3 (`:socket-http3`)** — the canonical, current handoff is **[`socket-http3/HANDOFF.md`](socket-http3/HANDOFF.md)**.
  Client is complete + interop-proven (issue #86, landed in PR #123); publishing + full target matrix +
  server push (RFC 9114 §4.6) done on branch `feat/http3-gaps`. Remaining: full server role, WebTransport
  (Phase 2), Apple `reset()` follow-up.
- **Repo-wide follow-ups** — see [`TODO.md`](TODO.md).

## Resolved (history — not current state)

The earlier content of this file was a socket-quic **CI-flake** investigation. That is **RESOLVED**: the
9 jvmTest flakes traced to a stream-command buffer-lifetime use-after-free (a cancelled `streamRead`/
`streamWrite` left an address-bearing command queued; the driver then wrote into freed memory →
glibc free-list corruption, surfacing as a "SIGSEGV in malloc" at a *later* test). Fixed in PR #101
(`NonCancellable` in-flight join before releasing the buffer) and released in 3.2.12; the reactive
writable-signal (PR #100) landed in 3.2.13. The full narrative is in git history for this file and in
the project memory notes.
