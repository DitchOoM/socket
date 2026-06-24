# h3spec conformance lane (Phase 2 — STEP 2)

External, deterministic proof of our HTTP/3 server's error-path conformance, using
[**h3spec**](https://github.com/kazu-yamamoto/h3spec) (kazu-yamamoto) as the probing client against our
**production** [`withHttp3Server`](../src/commonMain/kotlin/com/ditchoom/socket/http3/WithHttp3Server.kt).

h3spec opens a QUIC/HTTP3 connection and sends a fixed battery of malformed / edge-case HTTP/3 — a first
control frame that isn't SETTINGS, duplicate / reserved-HTTP2 setting ids, a second SETTINGS, frames on
the wrong stream, reserved-HTTP2 frame types, etc. — and checks the server closes the connection with the
RFC 9114 §8.1 error code the spec mandates. It is the **external counterpart** to our deterministic
in-process [`Http3ServerConnectionTests`](../src/commonTest/kotlin/com/ditchoom/socket/http3/Http3ServerConnectionTests.kt):
those assert the typed `Http3Violation` → error code over a `FakeQuicScope`; h3spec proves the same codes
travel the real wire (quiche transport + our hand-rolled H3 codec). It is the inverse of
[`../docker-interop`](../docker-interop), where *we* are the client probing aioquic.

## Layout

| File                | Role                                                                                  |
|---------------------|---------------------------------------------------------------------------------------|
| `Dockerfile`        | Builds the `h3spec` client binary from source (Haskell; slow on a cold cache).         |
| `run-h3spec.sh`     | Builds the image, starts our server, runs h3spec against it, tears it all down.        |
| `H3SpecServerMain`  | The server entrypoint (`../src/jvmTest/.../h3spec/H3SpecServerMain.kt`), run by Gradle. |

The server runs via the Gradle `:socket-http3:h3specServer` task (a `JavaExec` that stages the quiche
native lib onto the classpath, exactly like `:socket-http3:jvmTest`). Env knobs: `H3SPEC_PORT` (default
4433), `H3SPEC_CERT` / `H3SPEC_KEY` (default `testcerts/cert.{crt,key}`), `H3SPEC_QPACK_CAPACITY`
(default 0 = static QPACK).

## Run locally

```bash
# Needs Docker + a built :socket-quic-quiche quiche native on this host.
socket-http3/h3spec/run-h3spec.sh
# custom port / extra h3spec flags:
H3SPEC_PORT=4567 H3SPEC_ARGS='-v' socket-http3/h3spec/run-h3spec.sh
```

`run-h3spec.sh` exits with h3spec's exit code, so wired live it **gates** on conformance.

## Status: staged-but-dormant

This lane is wired but **not run by CI yet**:

- The build-linux workflow guards it behind a `run-h3spec` input that **defaults `false`** — flip it
  (`run-h3spec: true`) to enable the lane once it's proven.
- The whole redesign branch is **buffer-blocked**: `buffer 5.13.2` isn't on Maven Central, so the Linux
  job can't resolve dependencies until it publishes (same blocker as the `http3CodecFuzz` Jazzer lane).

Before flipping it on, expect first-run tuning: a self-signed `testcerts` cert (h3spec may want a trusted
CA or a `-k`-style flag), and triage of any h3spec cases that probe transport behaviour owned by quiche
rather than our H3 codec. Until then, the deterministic `Http3ServerConnectionTests` are the gating proof.
