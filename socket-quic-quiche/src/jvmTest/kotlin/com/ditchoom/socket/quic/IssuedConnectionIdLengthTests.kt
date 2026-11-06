package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every connection ID this library issues is 16 bytes on the wire — read off the datagrams themselves,
 * not off [generateScid] — and the server still routes a connection by them, through a handshake,
 * short-header data and a NAT rebind.
 *
 * Why 16 and not the RFC 9000 maximum of 20: Wireshark's QUIC dissector links a migrated 4-tuple to its
 * connection only when a first lookup, which assumes a 20-byte DCID, misses. A 20-byte server id makes
 * that lookup hit, the new path is never linked, and a capture of a rebind against a client using
 * zero-length ids shows the server's packets on the new path undecrypted — no PATH_CHALLENGE, so the
 * interop runner's rebind-port / rebind-addr checks fail. See [QUIC_ISSUED_CONN_ID_LEN].
 *
 * A [SelectorDatagramRelay] between the client and the server records every datagram both ways and
 * performs the rebind (a fresh upstream source port), exactly as [QuicPassiveMigrationTests] does.
 */
class IssuedConnectionIdLengthTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /** One relayed datagram's bytes, and whether it was sent before the rebind. */
    private class Seen(
        val bytes: ByteArray,
        val beforeRebind: Boolean,
    )

    @Test
    fun bothEndsIssue16ByteIds_andTheServerRoutesByThemAcrossARebind() =
        runQuicTest(timeout = 40.seconds) {
            skipOnMissingNativeLib(IssuedConnectionIdLengthTests::class) {
                withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                while (true) {
                                    val data = stream.read(8.seconds)
                                    if (data is ReadResult.Data) {
                                        try {
                                            stream.writeFully(data.buffer, 5.seconds)
                                        } finally {
                                            data.buffer.freeIfNeeded()
                                        }
                                    } else {
                                        break
                                    }
                                }
                                stream.close()
                            }
                        }

                    val rebound = AtomicBoolean(false)
                    val toServer = CopyOnWriteArrayList<Seen>()
                    val toClient = CopyOnWriteArrayList<Seen>()
                    lateinit var relay: SelectorDatagramRelay
                    relay =
                        SelectorDatagramRelay(
                            serverPort = port,
                            maxDatagram = 2048,
                            onClientToServer = { buf, _ ->
                                toServer += Seen(buf.copyRemaining(), beforeRebind = !rebound.get())
                                relay.writeToServer(buf)
                            },
                            onServerToClient = { buf, _ ->
                                toClient += Seen(buf.copyRemaining(), beforeRebind = !rebound.get())
                                relay.writeToClient(buf)
                            },
                        )
                    relay.start()
                    try {
                        withQuicConnection("127.0.0.1", relay.proxyPort, testQuicOptions, timeout = 10.seconds) {
                            val stream = openStream()
                            assertEquals("before", stream.echoOnce("before", readTimeout = 5.seconds))
                            rebound.set(true)
                            relay.rebindUpstream()
                            assertEquals(
                                "after",
                                stream.echoOnce("after", readTimeout = 9.seconds),
                                "the server lost the connection after the rebind",
                            )
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                        relay.close()
                    }

                    // The server's id: the SCID of every long-header packet it sent.
                    val serverIds = toClient.mapNotNull { longHeaderScid(it.bytes) }.distinctBy { it.toList() }
                    assertTrue(serverIds.isNotEmpty(), "the server sent no long-header packet")
                    serverIds.forEach {
                        assertEquals(16, it.size, "the server's connection id on the wire is ${it.size} bytes")
                    }
                    assertEquals(1, serverIds.size, "the server named more than one id in its long headers")
                    val serverId = serverIds.single()

                    // The client's id: the SCID of every long-header packet it sent after its first Initial
                    // (each carries the client's own id).
                    val clientIds = toServer.mapNotNull { longHeaderScid(it.bytes) }.distinctBy { it.toList() }
                    assertTrue(clientIds.isNotEmpty(), "the client sent no long-header packet")
                    clientIds.forEach {
                        assertEquals(16, it.size, "the client's connection id on the wire is ${it.size} bytes")
                    }

                    // Short-header packets carry the id the SERVER issued, and only that: before the
                    // rebind there is no reason for the client to change it, and the server routed every
                    // one of them (the echo came back) at the issued length.
                    val shortBefore = toServer.filter { it.beforeRebind && isShortHeader(it.bytes) }
                    assertTrue(shortBefore.isNotEmpty(), "no short-header packet reached the server before the rebind")
                    shortBefore.forEach {
                        assertTrue(
                            it.bytes.copyOfRange(1, 1 + serverId.size).contentEquals(serverId),
                            "a short-header packet to the server did not lead with the server's handshake id",
                        )
                    }
                    val shortAfter = toServer.filter { !it.beforeRebind && isShortHeader(it.bytes) }
                    assertTrue(shortAfter.isNotEmpty(), "no short-header packet reached the server after the rebind")
                }
            }
        }

    private suspend fun QuicByteStream.echoOnce(
        payload: String,
        readTimeout: Duration,
    ): String {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        val resp = read(readTimeout) { it.readString(it.remaining(), Charset.UTF8) }
        return if (resp is ScopedRead.Data) resp.value else "no_data"
    }

    private fun ByteBuffer.copyRemaining(): ByteArray {
        val out = ByteArray(remaining())
        duplicate().get(out)
        return out
    }

    private fun isShortHeader(d: ByteArray): Boolean = d.isNotEmpty() && (d[0].toInt() and 0x80) == 0

    /**
     * The SCID of a version-1 long header (RFC 9000 §17.2): flags, 4-byte version, DCID length + DCID,
     * SCID length + SCID. Null for a short header, a Version Negotiation packet or a truncated one.
     */
    private fun longHeaderScid(d: ByteArray): ByteArray? {
        if (d.size < 7 || (d[0].toInt() and 0x80) == 0) return null
        val version = (1..4).fold(0) { acc, i -> (acc shl 8) or (d[i].toInt() and 0xFF) }
        if (version != 1) return null
        val dcidLen = d[5].toInt() and 0xFF
        val scidLenAt = 6 + dcidLen
        if (scidLenAt >= d.size) return null
        val scidLen = d[scidLenAt].toInt() and 0xFF
        if (scidLenAt + 1 + scidLen > d.size) return null
        return d.copyOfRange(scidLenAt + 1, scidLenAt + 1 + scidLen)
    }
}
