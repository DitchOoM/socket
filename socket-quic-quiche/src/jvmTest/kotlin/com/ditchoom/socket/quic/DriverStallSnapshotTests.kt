package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [QuicheDriver.stallSnapshot] describes the live connection, not a default: bytes the peer sent and no
 * one has read show up as a readable stream with an idle reader and a buffered wake, and leave once read.
 */
class DriverStallSnapshotTests {
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

    @Test
    fun aStreamHoldingUnreadBytesIsReadableWithItsWakeBufferedUntilItIsRead() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(DriverStallSnapshotTests::class) {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val replied = CompletableDeferred<Unit>()
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    stream.read(10.seconds) { it.remaining() }
                                    stream.writeText(bufferFactory, "held")
                                    replied.complete(Unit)
                                    awaitCancellation()
                                }
                            }
                        try {
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                val driver = (this as QuicheBackedConnection).quicheDriver
                                val stream = openStream()
                                stream.writeText(bufferFactory, "go")
                                replied.await()

                                var held = snapshotOf(driver)
                                while (stream.streamId.id !in held.readable) {
                                    delay(10.milliseconds)
                                    held = snapshotOf(driver)
                                }
                                assertEquals(QuicRole.Client, held.role)
                                assertEquals(
                                    StreamWake(
                                        id = stream.streamId.id,
                                        read = StreamReadState.Idle,
                                        end = StreamEnd.Open,
                                        drainedChunks = ChunkQueue.Empty,
                                        readWake = WakeSignal.Buffered,
                                        writeWake = held.streams.single().writeWake,
                                    ),
                                    held.streams.single(),
                                    "the unread stream's slot: $held",
                                )
                                val path = held.paths.single()
                                assertEquals(PathEgressKind.Open, path.egress, "$held")
                                assertEquals(PathReader.Running, path.reader, "$held")
                                assertIs<QuicheTimer.Due>(held.quicheTimer, "an established connection's idle timer: $held")

                                val read = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                                assertEquals(ScopedRead.Data("held"), read)
                                val drained = snapshotOf(driver)
                                assertTrue(stream.streamId.id !in drained.readable, "a read stream is no longer readable: $drained")
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    private suspend fun QuicByteStream.writeText(
        factory: BufferFactory,
        text: String,
    ) {
        val out = factory.allocate(text.length)
        try {
            out.writeString(text, Charset.UTF8)
            out.resetForRead()
            writeFully(out, 5.seconds)
        } finally {
            out.freeIfNeeded()
        }
    }

    private suspend fun snapshotOf(driver: QuicheDriver): DriverStallSnapshot =
        assertIs<Inspected.Read<DriverStallSnapshot>>(driver.stallSnapshot()).value
}
