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
         * (RFC 9000 §17.2). The four long-header packet types live in bits `0x30`: Initial `0xC0`,
         * 0-RTT `0xD0`, Handshake `0xE0`, Retry `0xF0`. Each datagram below must be dropped (or rejected
         * by quiche) without crashing the process — `headerInfo` runs on every datagram before any
         * connection state exists, so an unchecked parse is a whole-process SIGABRT/SIGSEGV.
         *
         * This list is the deterministic regression floor; [HeaderInfoFuzzer] (jvmTest) drives the same
         * `headerInfo` entry point under coverage-guided Jazzer fuzzing to find inputs this list misses,
         * and these entries double as its seed corpus.
         */
        private val MALFORMED_DATAGRAMS: List<ByteArray> =
            listOf(
                // --- truncation / garbage ---------------------------------------------------------
                ByteArray(1), // single zero byte — far too short for any header
                ByteArray(20), // all-zero short header — DCID routes to nothing
                ByteArray(64) { 0xFF.toByte() }, // all-ones
                // long header v1 (Initial), truncated before DCID len
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01),
                // long header, unknown version 0xDEADBEEF, DCID len 4
                byteArrayOf(0xC0.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x04, 1, 2, 3, 4, 0x00),
                // long header v1 claiming DCID len 255 but truncated
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0xFF.toByte(), 1, 2, 3),
                byteArrayOf(0x40, 0x01, 0x02, 0x03), // short header (fixed bit) + garbage
                ByteArray(2000) { (it * 31 % 256).toByte() }, // oversized pseudo-random payload
                // long-header packet types (RFC 9000 §17.2): 0-RTT, Handshake, Retry
                byteArrayOf(0xD0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x04, 1, 2, 3, 4, 0x00), // 0-RTT v1, truncated
                byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x04, 1, 2, 3, 4, 0x00), // Handshake v1, truncated
                // Retry v1 (DCID/SCID len 0) with a 16-byte integrity tag but no token
                byteArrayOf(0xF0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x00) + ByteArray(16) { (0x10 + it).toByte() },
                // version 0x00000000 = Version Negotiation form; quiche must not treat us as a client
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x04, 1, 2, 3, 4, 0x04, 5, 6, 7, 8),
                // forced-negotiation GREASE version 0x1a2a3a4a (pattern 0x?a?a?a?a)
                byteArrayOf(0xC0.toByte(), 0x1A, 0x2A, 0x3A, 0x4A, 0x04, 1, 2, 3, 4, 0x00),
                // DCID len 21 (one over the v1 max of 20) followed by 21 id bytes, then SCID len 0
                ByteArray(28) { i ->
                    when (i) {
                        0 -> 0xC0.toByte() // Initial long header
                        1, 2, 3 -> 0x00 // version 0x00000001 …
                        4 -> 0x01
                        5 -> 0x15 // DCID len = 21 (illegal)
                        27 -> 0x00 // SCID len = 0
                        else -> (i - 6).toByte() // 21 DCID bytes
                    }
                },
                // SCID len 255 but truncated immediately after
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0xFF.toByte(), 1, 2, 3),
                // Initial, DCID/SCID 0, token-length 8-byte varint (0xC0 prefix) claiming a huge token
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x00) + ByteArray(8) { 0xFF.toByte() },
                // coalesced packets (two long headers in one datagram, RFC 9000 §12.2): Initial + truncated Handshake
                byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x04, 1, 2, 3, 4, 0x00) +
                    byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x04, 5, 6, 7, 8, 0x00),
                byteArrayOf(0x58, 9, 8, 7, 6, 5, 4, 3, 2), // short header with reserved bits set
                ByteArray(1200), // all-zero datagram at the UDP min-Initial size
            )
    }
}
