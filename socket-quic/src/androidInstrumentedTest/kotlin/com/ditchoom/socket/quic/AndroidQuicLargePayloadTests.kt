package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Large-payload / flow-control integration tests on Android — the Android port of
 * [QuicLargePayloadTestSuite] (issue #87 suite 3; unblocked by the #91 fix). Self-contained parallel
 * copy (androidInstrumentedTest can't see commonTest).
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicLargePayloadTests {
    private val tlsConfig get() = AndroidTestCerts.tlsConfig

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
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib { withTimeout(25.seconds) { transferAndVerify(1, SINGLE_PAYLOAD) } }
        }

    @Test
    fun multipleInterleavedStreamsTransferIntact() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib { withTimeout(25.seconds) { transferAndVerify(MULTI_STREAMS, MULTI_PER_STREAM) } }
        }

    private suspend fun transferAndVerify(
        streamCount: Int,
        perStreamBytes: Int,
    ) = coroutineScope {
        val results = Channel<Pair<Long, Boolean>>(capacity = streamCount)
        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
            val serverJob =
                launch(Dispatchers.IO) {
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
                            async(Dispatchers.IO) {
                                val stream = openStream()
                                stream.writeAllPattern(perStreamBytes)
                                stream.close()
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
                break
            }
        }
        return offset to ok
    }

    private suspend fun QuicByteStream.writeAllPattern(total: Int) {
        val pattern = ByteArray(total) { (it % 251).toByte() }
        val buf = BufferFactory.Default.allocate(total)
        buf.writeBytes(pattern)
        buf.resetForRead()
        writeAll(buf)
    }

    private suspend fun QuicByteStream.writeAll(buf: PlatformBuffer) {
        while (buf.remaining() > 0) {
            val n = write(buf, 15.seconds).count
            if (n > 0) buf.position(buf.position() + n) else delay(2.milliseconds)
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
        private const val SINGLE_PAYLOAD = 1 * 1024 * 1024
        private const val MULTI_STREAMS = 4
        private const val MULTI_PER_STREAM = 256 * 1024
        private const val STREAM_WINDOW = 128L * 1024
        private const val CONN_WINDOW = 8L * 1024 * 1024
    }
}
