package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            // Advertise RFC 9221 DATAGRAM support so datagram-enabled clients (incl.
            // the Apple group path, issue #109) can round-trip. Stream-only clients
            // are unaffected — this only advertises the transport parameter.
            datagrams = DatagramOptions(),
        )
    val tlsConfig = QuicTlsConfig(certChainPath = certPath, privKeyPath = keyPath)

    runBlocking(Dispatchers.IO) {
        withQuicServer(port = port, tlsConfig = tlsConfig, quicOptions = quicOptions) {
            // Signal readiness to the Gradle task
            println("READY port=$port")
            System.out.flush()

            // Accept connections — each runs in its own coroutine scope
            connections {
                // Streams and datagrams are echoed by independent child jobs: a
                // datagram-only client (e.g. the Apple group path, issue #109) never
                // opens a stream, and a stream client never sends datagrams. The
                // connection is held open until the PEER goes away (the datagram loop
                // observes ConnectionClosed), so a datagram client isn't cut off by the
                // stream-accept timeout — and per-connection scopes mean one lingering
                // connection never blocks the server's accept loop.
                val datagramJob =
                    launch {
                        try {
                            while (true) {
                                when (val r = receiveDatagram()) {
                                    is DatagramReceiveResult.Received -> {
                                        sendDatagram(r.buffer) // reads zero-copy; we still own it
                                        r.buffer.freeIfNeeded()
                                    }
                                    is DatagramReceiveResult.ConnectionClosed -> break
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                // Server-initiated stream (issue #112): open one stream TOWARD the client
                // and write a fixed greeting, so clients can exercise acceptStream() against a
                // real peer-initiated stream (e.g. an HTTP/3 server's control/QPACK streams).
                // Independent of the echo path; clients that don't accept it simply ignore it.
                val pushJob =
                    launch {
                        try {
                            val s = openStream()
                            val greeting = "HELLO\n"
                            val buf = BufferFactory.deterministic().allocate(greeting.length)
                            buf.writeString(greeting, Charset.UTF8)
                            buf.resetForRead()
                            s.write(buf, 10.seconds)
                            buf.freeNativeMemory()
                            s.close() // FIN, so the client sees the greeting then End
                        } catch (_: Exception) {
                        }
                    }
                val streamJob =
                    launch {
                        try {
                            // acceptStream has a timeout so a datagram-only / health-check
                            // connection doesn't keep this job parked forever.
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
                            // No stream opened (datagram-only or health-check) — fine.
                        }
                    }
                datagramJob.join()
                streamJob.cancel()
                pushJob.cancel()
            }
        }
    }
}
