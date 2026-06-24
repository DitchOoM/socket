@file:JvmName("H3SpecServerMain")

package com.ditchoom.socket.http3.h3spec

import com.ditchoom.socket.http3.withHttp3Server
import com.ditchoom.socket.quic.QuicTlsConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/**
 * A standalone HTTP/3 server that boots the PRODUCTION [withHttp3Server] for the external **h3spec**
 * conformance client (github.com/kazu-yamamoto/h3spec) to probe. h3spec sends fixed malformed / edge
 * HTTP/3 and asserts the server closes the connection with the RFC 9114 §8.1 error code the spec
 * mandates — the external proof of the Phase-0 / STEP-1 typed [com.ditchoom.socket.http3.Http3Violation]
 * enforcement (control-stream framing, SETTINGS rules, reserved-HTTP2 frames, request framing).
 *
 * Launched by the Gradle `:socket-http3:h3specServer` task (which puts the quiche native lib on the
 * classpath so the QUIC transport links), or by `socket-http3/h3spec/run-h3spec.sh`, which starts this,
 * runs h3spec against it, and tears it down. Configured purely by env so no args plumbing is needed:
 *
 *  - `H3SPEC_PORT`           UDP port to bind (default 4433; 0 = ephemeral, printed in the ready line).
 *  - `H3SPEC_CERT`           PEM certificate-chain path (default `testcerts/cert.crt`, relative to the
 *                            module dir, which the Gradle task sets as the working dir).
 *  - `H3SPEC_KEY`            PEM private-key path (default `testcerts/cert.key`).
 *  - `H3SPEC_QPACK_CAPACITY` server QPACK dynamic-table capacity (default 0 = static QPACK, always legal).
 *
 * The process serves until killed: [awaitCancellation] never returns, and the runner SIGTERMs it after
 * h3spec finishes. The `h3spec server ready on udp/<port>` line (flushed) is the runner's readiness gate.
 */
fun main() {
    val requestedPort = System.getenv("H3SPEC_PORT")?.toIntOrNull() ?: 4433
    val certPath = System.getenv("H3SPEC_CERT") ?: "testcerts/cert.crt"
    val keyPath = System.getenv("H3SPEC_KEY") ?: "testcerts/cert.key"
    val qpackCapacity = System.getenv("H3SPEC_QPACK_CAPACITY")?.toLongOrNull() ?: 0L

    runBlocking {
        withHttp3Server(
            port = requestedPort,
            tlsConfig = QuicTlsConfig(certChainPath = certPath, privKeyPath = keyPath),
            qpackCapacity = qpackCapacity,
            // h3spec exercises framing / control-stream / error-path behaviour, not body content, so a
            // minimal always-legal 200 (HEADERS + FIN) is the right baseline for its valid-request cases.
            onRequest = { response.send(200) },
        ) {
            // Readiness marker the runner greps for before launching h3spec (port resolves an ephemeral bind).
            println("h3spec server ready on udp/$port")
            System.out.flush()
            awaitCancellation()
        }
    }
}
