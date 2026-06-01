package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Shared **large-payload / flow-control** integration suite (issue #87 suite 3; unblocked by the #91
 * fix). Transfers MB-scale data — single-stream and across multiple interleaved streams — through a
 * stream flow-control window deliberately set **smaller than the payload**, so the transfer can only
 * complete by cycling `MAX_STREAM_DATA`. Asserts every byte arrives intact and in order.
 *
 * This is the end-to-end exercise of two driver fixes that this work produced:
 *  - the `QUICHE_ERR_DONE` write back-pressure fix (#92) — a write larger than the window no longer
 *    throws; [writeAll] retries on a 0-accept;
 *  - the #91 FIN-coalescing fix — when the last data chunk and the FIN arrive together, the server's
 *    read no longer wedges, so a bulk stream (single or one of several concurrent) reliably completes.
 *
 * Same 3-tier shape as the other suites: commonTest abstract + per-platform [testTlsConfig]; Android has
 * a self-contained parallel copy (`AndroidQuicLargePayloadTests`).
 *
 * **Shape.** A one-directional bulk transfer: the client writes the pattern and FINs (`stream.close()`
 * is a full close, and reading MB-scale back on the same stream would deadlock on the windows). The
 * server reads each stream to FIN, verifying every byte equals `offset % 251` — a position-aware check
 * that catches corruption, reordering, and truncation — and reports `(count, ok)`.
 *
 * **Determinism.** Fixed payload sizes / stream counts and an exact per-byte check; the connection
 * window is generous so only the per-stream window cycles (a tight shared connection window across the
 * interleaved streams turns the write retry into a load-sensitive livelock — see the suite-3 history).
 */
abstract class QuicLargePayloadTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Platform hook for skip-on-missing-native-lib (JVM converts `UnsatisfiedLinkError` to a skip). */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            flowControl =
                FlowControl(
                    initialMaxData = CONN_WINDOW,
                    initialMaxStreamDataBidiLocal = STREAM_WINDOW,
                    initialMaxStreamDataBidiRemote = STREAM_WINDOW,
                    initialMaxStreamDataUni = STREAM_WINDOW,
                    initialMaxStreamsBidi = 64,
                ),
        )

    @Test
    fun largePayloadTransfersIntactOnOneStream() =
        runQuicTest {
            wrapTestBody { transferAndVerify(streamCount = 1, perStreamBytes = SINGLE_PAYLOAD) }
        }

    @Test
    fun multipleInterleavedStreamsTransferIntact() =
        runQuicTest {
            wrapTestBody { transferAndVerify(streamCount = MULTI_STREAMS, perStreamBytes = MULTI_PER_STREAM) }
        }

    // ---- orchestration -----------------------------------------------------------------------------

    private suspend fun transferAndVerify(
        streamCount: Int,
        perStreamBytes: Int,
    ) = coroutineScope {
        // Every stream carries the same pattern of the same length, so we just collect [streamCount].
        val results = Channel<Pair<Long, Boolean>>(capacity = streamCount)

        withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
            val serverJob =
                launch {
                    connections {
                        streams().collect { stream ->
                            launch {
                                results.send(stream.verifyPattern())
                                stream.close()
                            }
                        }
                    }
                }

            try {
                withQuicConnection("127.0.0.1", port, options, timeout = 15.seconds) {
                    (0 until streamCount)
                        .map {
                            async {
                                val stream = openStream()
                                stream.writeAllPattern(perStreamBytes)
                                stream.close() // FIN
                            }
                        }.awaitAll()

                    repeat(streamCount) {
                        val (count, ok) = results.receive()
                        assertEquals(perStreamBytes.toLong(), count, "stream transfer truncated/overran ($count of $perStreamBytes bytes)")
                        assertTrue(ok, "stream payload corrupted or reordered")
                    }
                }
            } finally {
                serverJob.cancel()
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Server side: read to FIN, verifying every byte equals `offset % 251`. Returns (bytesReceived, allMatched). */
    private suspend fun QuicByteStream.verifyPattern(): Pair<Long, Boolean> {
        var offset = 0L
        var ok = true
        while (true) {
            val r = read(15.seconds)
            if (r is ReadResult.Data) {
                val bytes = r.buffer.readByteArray(r.buffer.remaining())
                r.buffer.freeIfNeeded()
                for (b in bytes) {
                    if (b != ((offset % 251).toByte())) ok = false
                    offset++
                }
            } else {
                break // End (FIN) or Reset
            }
        }
        return offset to ok
    }

    private suspend fun QuicByteStream.writeAllPattern(total: Int) {
        val pattern = ByteArray(total) { (it % 251).toByte() }
        val buf = BufferFactory.deterministic().allocate(total)
        buf.writeBytes(pattern)
        buf.resetForRead()
        try {
            writeAll(buf)
        } finally {
            buf.freeNativeMemory()
        }
    }

    /**
     * Write the whole buffer, advancing by the accepted count. No delay-poll on back-pressure: a full
     * flow-control window makes `write` park reactively on the stream's writable-signal and resume when
     * the peer's `MAX_STREAM_DATA` reopens it, so each call returns only once it has made progress.
     */
    private suspend fun QuicByteStream.writeAll(buf: PlatformBuffer) {
        while (buf.remaining() > 0) {
            val n = write(buf, 15.seconds).count
            buf.position(buf.position() + n)
        }
    }

    private companion object {
        private const val SINGLE_PAYLOAD = 1 * 1024 * 1024
        private const val MULTI_STREAMS = 4
        private const val MULTI_PER_STREAM = 256 * 1024

        /** Per-stream window below the payload so the transfer must cycle MAX_STREAM_DATA. */
        private const val STREAM_WINDOW = 128L * 1024

        /** Generous so the connection window is never the bottleneck (avoids a load-sensitive livelock). */
        private const val CONN_WINDOW = 8L * 1024 * 1024
    }
}
