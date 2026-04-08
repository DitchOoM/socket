package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Standalone QUIC echo server for Android instrumented tests.
 *
 * Usage: QuicEchoTestServerKt <cert.crt> <cert.key>
 *
 * Binds on port 4433, echoes data back on each stream.
 * Prints "READY port=4433" to stdout when accepting connections.
 * Runs until killed (SIGTERM/SIGINT).
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: QuicEchoTestServer <cert.crt> <cert.key>" }
    val certPath = args[0]
    val keyPath = args[1]

    val port = 4433
    val quicOptions = QuicOptions(
        alpnProtocols = listOf("test"),
        verifyPeer = false,
        idleTimeout = 30.seconds,
    )
    val tlsConfig = QuicTlsConfig(certChainPath = certPath, privKeyPath = keyPath)

    runBlocking(Dispatchers.IO) {
        val engine = defaultQuicServerEngine()
        val server = engine.bind(port = port, tlsConfig = tlsConfig, quicOptions = quicOptions)

        // Signal readiness to the Gradle task
        println("READY port=${server.port}")
        System.out.flush()

        // Accept connections — each runs in its own coroutine scope
        server.connections {
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
