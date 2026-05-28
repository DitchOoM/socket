package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Standalone QUIC echo server for Android instrumented tests + the
 * test-harness Docker container (test-harness/quic-echo/Dockerfile).
 *
 * Usage: QuicEchoTestServerKt <cert.crt> <cert.key> [port]
 *
 * Binds on the given port (default 4433), echoes data back on each stream.
 * Prints "READY port=<port>" to stdout when accepting connections.
 * Runs until killed (SIGTERM/SIGINT).
 *
 * Two-arg form keeps backwards compatibility with the Android instrumented-
 * test flow (`startQuicTestServer` Gradle task). The optional third arg lets
 * the harness container bind on `14433` directly without docker port-mapping
 * trickery — QUIC servers are generally fine with NAT/port translation
 * (quiche only echoes what it bound to), but keeping the in-container port
 * == published port avoids any surprises.
 */
fun main(args: Array<String>) {
    require(args.size == 2 || args.size == 3) { "Usage: QuicEchoTestServer <cert.crt> <cert.key> [port]" }
    val certPath = args[0]
    val keyPath = args[1]

    val port = if (args.size == 3) args[2].toInt() else 4433
    val quicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
        )
    val tlsConfig = QuicTlsConfig(certChainPath = certPath, privKeyPath = keyPath)

    runBlocking(Dispatchers.IO) {
        withQuicServer(port = port, tlsConfig = tlsConfig, quicOptions = quicOptions) {
            // Signal readiness to the Gradle task
            println("READY port=$port")
            System.out.flush()

            // Accept connections — each runs in its own coroutine scope
            connections {
                try {
                    // acceptStream has a timeout so health-check connections
                    // (that connect but never open a stream) don't block the server
                    val stream = withTimeout(3.seconds) { acceptStream() }
                    try {
                        while (true) {
                            val data = stream.read(30.seconds)
                            if (data is ReadResult.Data) {
                                stream.write(data.buffer, 10.seconds)
                            } else {
                                break
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        stream.close()
                    }
                } catch (_: Exception) {
                    // Health-check connection with no stream — just let it close
                }
            }
        }
    }
}
