# QPACK differential oracle (ls-qpack / pylsqpack)

An independent QPACK (RFC 9204) implementation, exposed over a tiny HTTP line protocol, for
**differential fuzzing** of our hand-rolled codec by `QpackDifferentialInteropTests` (`src/jvmTest`).

## Why

The in-process `Http3RoundTripFuzzTests` runs our QPACK encoder through *our own* decoder, so a bug
symmetric across both halves hides. aioquic's `pylsqpack` wraps **ls-qpack** — a completely independent C
implementation — so the differential test can cross-check both directions the loopback cannot isolate:

- **ours-encode → ref-decode**: our encoder's field section + encoder-stream inserts must decode in
  ls-qpack back to the exact header list. (`Http3DockerInteropTests` proves this only over full H3/QUIC;
  here it is raw QPACK, per-section, fuzzed.)
- **ref-encode → ours-decode**: ls-qpack's field section + encoder stream must decode in *our* decoder
  back to the exact header list — a direction with **no other coverage**.

It runs all header lists through capacity regimes 0 (static-only), 256 (small/eviction), and 4096.

## Run

```bash
# Docker:
./run-qpack-diff.sh start        # detached, blocks until ready (CI)
./gradlew :socket-http3:jvmTest --tests '*QpackDifferentialInteropTests*'
./run-qpack-diff.sh stop

# Or a venv (no Docker needed — pylsqpack ships wheels):
python3 -m venv v && v/bin/pip install pylsqpack
v/bin/python qpack-diff-server.py &
./gradlew :socket-http3:jvmTest --tests '*QpackDifferentialInteropTests*'
```

The test **skips itself** if the oracle is not reachable on `127.0.0.1:4434`, so it never flaky-fails CI
(it is dormant until the oracle is started — same discipline as `docker-interop/`). Override the port with
`QPACK_DIFF_PORT`.

## Wire protocol

Deliberately hex+decimal, no JSON (no escaping to get wrong); every encoder/decoder is fresh per request.
See the docstring in `qpack-diff-server.py`.

## Known divergence

An **empty** field section is spec-legal (RFC 9204 §4.5) and our codec round-trips it, but ls-qpack's
`lsqpack_dec_header_in` rejects a zero-field block and a real HTTP/3 message never has one. The test
generates only non-empty header lists for the external differential; the empty section is covered
in-process by `Http3RoundTripFuzzTests`.
