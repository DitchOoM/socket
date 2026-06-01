package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Shared **malformed-packet / parser fuzz** test suite (issue #87, suite #4). Feeds a fixed set of
 * truncated / garbage / wrong-version / over-long-CID / oversized datagrams to the server's UDP port and
 * asserts the server's header-parse (`headerInfo`) + driver recv path survives — both before any
 * connection and while a legitimate connection is live. High value given the K/N sockaddr / cinterop
 * SIGABRT history: a single un-checked parse on the recv path takes down the whole process, so "the
 * server still works after the blast" is a real crash check, not a no-op.
 *
 * Same 3-tier shape as the other suites: commonTest abstract + per-platform [testTlsConfig] and a
 * [sendRawDatagram] primitive (a plain UDP send, no QUIC); Android has a self-contained parallel copy
 * (`AndroidQuicMalformedPacketTests`).
 *
 * **Determinism.** The malformed datagrams are a fixed, hand-written list — not random fuzzing — and the
 * assertion is an exact echo round-trip after the blast. A crash (SIGABRT/SIGSEGV) takes the whole test
 * binary down; a parse that wrongly created/corrupted connection state would fail the post-blast echo.
 */
abstract class QuicMalformedPacketTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Platform hook for skip-on-missing-native-lib (JVM converts `UnsatisfiedLinkError` to a skip). */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /** Send a single raw UDP datagram (no QUIC) of [bytes] to `127.0.0.1:[port]`. */
    abstract suspend fun sendRawDatagram(
        port: Int,
        bytes: ByteArray,
    )

    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    // ---- tests -------------------------------------------------------------------------------------

    /** Blast malformed datagrams at the server, then a legitimate client must still connect and echo. */
    @Test
    fun serverSurvivesMalformedDatagramsThenServesLegitClient() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val serverJob = launch { echoEveryStream() }
                    try {
                        for (datagram in MALFORMED_DATAGRAMS) sendRawDatagram(port, datagram)

                        withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
                            val stream = openStream()
                            assertEquals("alive", stream.echoOnce("alive"), "server did not serve a legit client after malformed datagrams")
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /** With a live connection, blast malformed datagrams at the server; the connection must keep echoing. */
    @Test
    fun liveConnectionSurvivesMalformedDatagrams() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val serverJob = launch { echoEveryStream() }
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

    // ---- helpers -----------------------------------------------------------------------------------

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
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        try {
            write(out, 5.seconds)
        } finally {
            out.freeNativeMemory()
        }
        val resp = read(5.seconds)
        return if (resp is ReadResult.Data) {
            val s = resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8)
            resp.buffer.freeIfNeeded()
            s
        } else {
            "no_data"
        }
    }

    companion object {
        /**
         * Fixed malformed datagrams targeting the server's `headerInfo` + recv path. QUIC long header =
         * first byte `0x80 | …`, 4-byte version, 1-byte DCID len + DCID, 1-byte SCID len + SCID, …
         * Each must be dropped (or rejected by quiche) without crashing the process.
         */
        private val MALFORMED_DATAGRAMS: List<ByteArray> =
            listOf(
                ByteArray(1), // single zero byte — far too short for any header
                ByteArray(20), // all-zero short header — DCID routes to nothing
                ByteArray(64) { 0xFF.toByte() }, // all-ones
                // long header v1, truncated before DCID len
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01),
                // long header, unknown version 0xDEADBEEF, DCID len 4
                byteArrayOf(0xC0.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x04, 1, 2, 3, 4, 0x00),
                // long header v1 claiming DCID len 255 but truncated
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0xFF.toByte(), 1, 2, 3),
                byteArrayOf(0x40, 0x01, 0x02, 0x03), // short header (fixed bit) + garbage
                ByteArray(2000) { (it * 31 % 256).toByte() }, // oversized pseudo-random payload
            )
    }
}
