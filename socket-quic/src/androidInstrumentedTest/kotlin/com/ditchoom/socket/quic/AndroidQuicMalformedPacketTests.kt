package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Malformed-packet / parser fuzz tests on Android — the Android port of the JVM/Linux
 * [QuicMalformedPacketTestSuite] (issue #87, suite #4). `androidInstrumentedTest` can't see `commonTest`,
 * so this is a self-contained parallel copy. Sends a fixed set of malformed datagrams to the server's
 * UDP port and asserts the header-parse + recv path survives and still serves a legitimate client.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicMalformedPacketTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private val tlsConfig get() = AndroidTestCerts.tlsConfig

    @Test
    fun serverSurvivesMalformedDatagramsThenServesLegitClient() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(25.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                        val serverJob = launch(Dispatchers.IO) { echoEveryStream() }
                        try {
                            for (datagram in MALFORMED_DATAGRAMS) sendRawDatagram(port, datagram)
                            withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
                                val stream = openStream()
                                assertEquals(
                                    "alive",
                                    stream.echoOnce("alive"),
                                    "server did not serve a legit client after malformed datagrams",
                                )
                                stream.close()
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun liveConnectionSurvivesMalformedDatagrams() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(25.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                        val serverJob = launch(Dispatchers.IO) { echoEveryStream() }
                        try {
                            withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
                                val stream = openStream()
                                assertEquals("before", stream.echoOnce("before"), "echo failed before the blast")
                                for (datagram in MALFORMED_DATAGRAMS) sendRawDatagram(port, datagram)
                                assertEquals("after", stream.echoOnce("after"), "live connection broke after malformed datagrams")
                                stream.close()
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------

    private suspend fun sendRawDatagram(
        port: Int,
        bytes: ByteArray,
    ) {
        DatagramChannel.open().use { channel ->
            channel.send(ByteBuffer.wrap(bytes), InetSocketAddress("127.0.0.1", port))
        }
    }

    private suspend fun QuicServer.echoEveryStream() {
        connections {
            val stream = acceptStream()
            while (true) {
                val data = stream.read(8.seconds)
                if (data is ReadResult.Data) {
                    stream.write(data.buffer, 5.seconds)
                } else {
                    break
                }
            }
            stream.close()
        }
    }

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        val resp = read(5.seconds)
        return if (resp is ReadResult.Data) {
            val s = resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8)
            resp.buffer.freeIfNeeded()
            s
        } else {
            "no_data"
        }
    }

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    private companion object {
        // Keep in sync with QuicMalformedPacketTestSuite.MALFORMED_DATAGRAMS (commonTest can't be
        // seen from androidInstrumentedTest). See that file for the RFC 9000 §17.2 header-form notes.
        private val MALFORMED_DATAGRAMS: List<ByteArray> =
            listOf(
                // truncation / garbage
                ByteArray(1),
                ByteArray(20),
                ByteArray(64) { 0xFF.toByte() },
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01),
                byteArrayOf(0xC0.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x04, 1, 2, 3, 4, 0x00),
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0xFF.toByte(), 1, 2, 3),
                byteArrayOf(0x40, 0x01, 0x02, 0x03),
                ByteArray(2000) { (it * 31 % 256).toByte() },
                // long-header packet types: 0-RTT, Handshake, Retry
                byteArrayOf(0xD0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x04, 1, 2, 3, 4, 0x00),
                byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x04, 1, 2, 3, 4, 0x00),
                byteArrayOf(0xF0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x00) + ByteArray(16) { (0x10 + it).toByte() },
                // version negotiation / GREASE versions
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x04, 1, 2, 3, 4, 0x04, 5, 6, 7, 8),
                byteArrayOf(0xC0.toByte(), 0x1A, 0x2A, 0x3A, 0x4A, 0x04, 1, 2, 3, 4, 0x00),
                // invalid connection-id lengths
                ByteArray(28) { i ->
                    when (i) {
                        0 -> 0xC0.toByte()
                        1, 2, 3 -> 0x00
                        4 -> 0x01
                        5 -> 0x15
                        27 -> 0x00
                        else -> (i - 6).toByte()
                    }
                },
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0xFF.toByte(), 1, 2, 3),
                // Initial token-length / length varint edges
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x00) + ByteArray(8) { 0xFF.toByte() },
                // coalesced packets
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x04, 1, 2, 3, 4, 0x00) +
                    byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x04, 5, 6, 7, 8, 0x00),
                // short header reserved bits / min-Initial-size
                byteArrayOf(0x58, 9, 8, 7, 6, 5, 4, 3, 2),
                ByteArray(1200),
            )
    }
}
