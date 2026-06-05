# HTTP/3 QPACK interop server (aioquic)

A minimal third-party HTTP/3 server — [aioquic](https://github.com/aiortc/aioquic), which decodes QPACK
with **lsqpack** — used to validate our client's QPACK encoder (including the **dynamic-table eviction
tune**) against an *independent* implementation, deterministically and offline.

## Why

The in-process loopback suite round-trips our encoder through *our own* decoder, so a bug symmetric
across both halves could hide. A foreign decoder can't be wrong the same way: if our evicting encoder
ever references an entry the peer has evicted (RFC 9204 §2.1.3 violation), lsqpack raises a decompression
/ decoder-stream error and the connection dies — failing the test. Running on `127.0.0.1` loopback also
avoids the UDP/443 egress flakiness that makes the public-endpoint interop test skip on WSL2/CI.

## Run it

```bash
./run-server.sh                              # build + run on udp/4433 (foreground)
QPACK_MAX_TABLE_CAPACITY=256 ./run-server.sh # tiny table → eviction on nearly every request
./run-server.sh stop                         # stop + remove the container
```

Then, in another shell:

```bash
./gradlew :socket-http3:jvmTest      --tests '*Http3DockerInteropTests*'
./gradlew :socket-http3:linuxX64Test --tests '*Http3DockerInteropTests*'
```

`Http3DockerInteropTests` drives 64 requests on one connection, each with a distinct ~220-octet custom
header, to push the encoder's dynamic table past capacity so it evicts, re-references, and re-inserts.
The server echoes every `x-*` request header back as `echo-x-*`; the test asserts each echo matches —
proving lsqpack decoded our evicting encoder stream the whole way through.

**The test skips itself (never fails) when the server isn't reachable on `127.0.0.1:4433`,** so it is safe
to leave in the suite. It only *fails* on a post-handshake error — i.e. a real QPACK regression.

## Knobs (env vars)

| Var | Default | Effect |
|-----|---------|--------|
| `QPACK_MAX_TABLE_CAPACITY` | `4096` | server-advertised QPACK table ceiling; our encoder is bounded by it, so a smaller value forces eviction sooner |
| `QPACK_BLOCKED_STREAMS` | `16` | server-advertised QPACK_BLOCKED_STREAMS |
| `HTTP3_PORT` | `4433` | UDP listen port |

## CI

Not wired into CI by default — it needs a Docker sidecar. To enable, run the container as a service step
(publish `4433/udp`) before the Gradle test job; the test auto-skips if it's absent, so adding it is
non-breaking. netem can be layered on the container's veth (`tc qdisc add dev … netem delay/loss/reorder`)
to add packet reordering between the QPACK encoder stream and the request streams.
