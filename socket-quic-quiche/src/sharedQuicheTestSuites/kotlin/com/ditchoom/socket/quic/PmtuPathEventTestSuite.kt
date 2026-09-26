package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A client that drains quiche's path events keeps working after PMTU discovery raises the path's PMTU.
 *
 * With PMTU discovery on, quiche reports every change of a path's validated PMTU as a
 * `QUICHE_PATH_EVENT_PMTU_UPDATED` path event. A migration-capable client drains path events on every
 * turn of its driver loop, so each backend's `connPathEventNext` decodes that event: an unmapped value
 * there is an exception thrown out of the driver loop, which kills the connection the first time a
 * PMTU probe is acknowledged.
 *
 * The witness that the event was emitted is the path's PMTU reaching [QuicOptions.maxUdpPayloadSize]:
 * PMTU discovery starts every path at the 1200-byte QUIC minimum, and quiche emits the event whenever
 * the validated value changes.
 *
 * A suite with per-platform members because each backend decodes path events separately: JVM (JNI by
 * default, FFM with `-PquicheJvmBackend=ffm`), Apple cinterop, Linux cinterop and Android JNI.
 */
abstract class PmtuPathEventTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Same hook as the other suites: the JVM/Android members turn a missing native into a typed skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val options =
        QuicOptions(
            alpnProtocols = listOf(PROTOCOL),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            enablePmtuDiscovery = true,
        )

    @Test
    fun aConnectionKeepsWorkingAfterPmtuDiscoveryRaisesThePathPmtu() =
        runQuicTest(timeout = 45.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val serverJob =
                        launch {
                            connections {
                                repeat(EXCHANGES) {
                                    val stream = acceptStream()
                                    val payload = stream.readToEnd()
                                    stream.writeText("ack:$payload")
                                    stream.shutdownSend()
                                }
                            }
                        }
                    try {
                        withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
                            val driver = (this as QuicheBackedConnection).quicheDriver
                            assertIs<MigrationCapability.Supported>(
                                driver.migration,
                                "this test needs a client that drains path events, which only a migration-capable one does",
                            )
                            assertEquals("ack:before", openStream().exchange("before"))

                            val pmtu = awaitPmtu(driver, options.maxUdpPayloadSize.toLong())
                            assertEquals(
                                options.maxUdpPayloadSize.toLong(),
                                pmtu,
                                "PMTU discovery never raised the loopback path's PMTU to maxUdpPayloadSize, so no " +
                                    "PmtuUpdated event was emitted and this run proves nothing about decoding it",
                            )

                            assertEquals(
                                "ack:after",
                                openStream().exchange("after"),
                                "the connection must survive draining the PmtuUpdated path event",
                            )
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /** The active path's PMTU once it reaches [target], or the last value read when [PMTU_WAIT] runs out. */
    private suspend fun awaitPmtu(
        driver: QuicheDriver,
        target: Long,
    ): Long? {
        val start = TimeSource.Monotonic.markNow()
        var last: Long? = null
        while (start.elapsedNow() < PMTU_WAIT) {
            last = driver.stats().pathStats?.pmtu
            if (last == target) return last
            delay(20.milliseconds)
        }
        return last
    }

    private suspend fun QuicByteStream.exchange(payload: String): String {
        writeText(payload)
        shutdownSend()
        return readToEnd()
    }

    private suspend fun QuicByteStream.writeText(payload: String) {
        writeFully(text(payload), STREAM_DEADLINE)
    }

    private suspend fun QuicByteStream.readToEnd(): String {
        val sb = StringBuilder()
        while (true) {
            when (val chunk = read(STREAM_DEADLINE) { it.readString(it.remaining(), Charset.UTF8) }) {
                is ScopedRead.Data -> sb.append(chunk.value)
                ScopedRead.End, ScopedRead.Reset -> return sb.toString()
            }
        }
    }

    private fun text(payload: String): ReadBuffer {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        return out
    }

    private companion object {
        const val PROTOCOL = "pmtu-test"
        const val EXCHANGES = 2
        val STREAM_DEADLINE = 5.seconds
        val PMTU_WAIT = 10.seconds
    }
}
