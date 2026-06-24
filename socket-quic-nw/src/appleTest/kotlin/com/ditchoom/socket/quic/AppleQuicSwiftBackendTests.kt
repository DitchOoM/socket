@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Direct exercise of the OS-26 Swift-API QUIC backend ([connectQuicSwift] / [buildAppleQuicSwiftServer])
 * through the cross-platform [QuicConnection] / [QuicScope] / [QuicServer] contracts — bypassing engine
 * selection (that OS gating is step 5). The headline test proves the whole point of issue #173: on the
 * new `NetworkConnection<QUIC>` API, **unreliable datagrams and bidirectional streams coexist on a single
 * connection**, the thing the legacy `nw_connection_group` backend cannot do.
 *
 * The server presents the W3C `pinned` EC P-256 identity; the client pins its leaf hash (HashOnly), so
 * the self-signed cert validates via the shim's Swift-side comparison. Skipped on `--standalone` Apple
 * simulators where the NW QUIC datapath is unreachable (see [shouldSkipQuicHarnessOnSimulator]).
 */
class AppleQuicSwiftBackendTests {
    private val alpn = listOf("nwq26-backend-test")

    @Test
    fun swiftBackend_streamAndDatagramCoexistOnOneConnection() {
        if (shouldSkipQuicHarnessOnSimulator()) return
        runBlocking {
            withTimeout(25.seconds) {
                val serverOptions = quicOptions(serverHashes = emptyList())
                val server =
                    buildAppleQuicSwiftServer(
                        port = 0,
                        host = "127.0.0.1",
                        tlsConfig = appleQuicPinnedTlsConfig("pinned"),
                        quicOptions = serverOptions,
                        timeout = 10.seconds,
                    )

                val serverDone = CompletableDeferred<Unit>()
                val serverJob =
                    launch {
                        server.connections {
                            // Datagram echo loop (concurrent with the stream handling below).
                            val dgJob =
                                launch {
                                    while (true) {
                                        when (val r = receiveDatagram()) {
                                            is DatagramReceiveResult.Received -> sendDatagram(r.buffer)
                                            is DatagramReceiveResult.ConnectionClosed -> break
                                        }
                                    }
                                }
                            // Stream echo: accept the client's bidi stream, read to FIN, reply "echo:<req>".
                            val stream = acceptStream()
                            val request = stream.readToEnd()
                            stream.writeString("echo:$request")
                            stream.shutdownSend()
                            serverDone.await()
                            dgJob.cancel()
                        }
                    }

                val client =
                    connectQuicSwift(
                        hostname = "127.0.0.1",
                        port = server.port,
                        quicOptions = quicOptions(serverHashes = listOf(pinnedLeafHash("pinned"))),
                        connectionOptions = TransportConfig(),
                        timeout = 10.seconds,
                    )

                // Stream round-trip.
                val stream = client.openStream()
                stream.writeString("hello")
                stream.shutdownSend()
                val echoed = stream.readToEnd()
                assertEquals("echo:hello", echoed, "bidi stream round-trip over the Swift backend")

                // Datagram round-trip ON THE SAME CONNECTION (coexisting with the stream above).
                client.sendDatagram(buffer("ping"))
                val dg = client.receiveDatagram()
                check(dg is DatagramReceiveResult.Received) { "expected a datagram echo, got $dg" }
                assertEquals("ping", dg.buffer.readString(dg.buffer.remaining(), Charset.UTF8), "datagram round-trip")

                serverDone.complete(Unit)
                client.close()
                server.close()
                serverJob.cancel()
            }
        }
    }

    // --- helpers ---

    private fun quicOptions(serverHashes: List<CertificateHash>) =
        QuicOptions(
            alpnProtocols = alpn,
            idleTimeout = 30.seconds,
            datagrams = DatagramOptions(),
            serverCertificateHashes = serverHashes,
            certificateHashVerification = CertificateHashVerification.HashOnly,
        )

    private fun pinnedLeafHash(name: String): CertificateHash {
        val hex = appleReadFileText(appleTestCertPath("$name.sha256")).trim()
        val bytes = ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    private fun buffer(s: String): ReadBuffer {
        val bytes = s.encodeToByteArray()
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        buf.writeString(s, Charset.UTF8)
        buf.resetForRead()
        return buf
    }

    private suspend fun QuicByteStream.writeString(s: String) {
        write(buffer(s), 5.seconds)
    }

    private suspend fun QuicByteStream.readToEnd(): String {
        val sb = StringBuilder()
        while (true) {
            when (val r = read(5.seconds)) {
                is ReadResult.Data -> sb.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                else -> break // End / Reset
            }
        }
        return sb.toString()
    }
}
