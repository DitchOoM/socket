package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** A stream that moves bulk data over the core: every byte arrives, and its reads grow from the small first buffer to the largest. */
class CoreBulkTransferTests {
    @Test
    fun sixteenMebibytesEchoThroughTheCoreAndTheReadBufferGrowsToItsCeiling() =
        runBlocking {
            skipOnMissingNativeLib(CoreBulkTransferTests::class) {
                withEchoServer { port ->
                    val client = connectCore(port)
                    val stream = client.connection.openStream()
                    val capacities = ArrayList<Int>()
                    var received = 0L
                    coroutineScope {
                        launch {
                            val chunk =
                                BufferFactory.Default.allocate(CHUNK).apply {
                                    repeat(CHUNK) { writeByte(it.toByte()) }
                                }
                            repeat(TOTAL / CHUNK) {
                                chunk.position(0)
                                chunk.setLimit(CHUNK)
                                stream.writeFully(chunk, 30.seconds)
                            }
                        }
                        while (received < TOTAL) {
                            val result = stream.read(30.seconds)
                            if (result !is ReadResult.Data) fail("the echo ended after $received of $TOTAL bytes: $result")
                            received += result.buffer.remaining()
                            capacities += (result.buffer as PlatformBuffer).capacity
                            result.buffer.freeIfNeeded()
                        }
                    }
                    client.connection.close(QuicError.NoError)
                    assertEquals(TOTAL.toLong(), received)
                    assertEquals(2_048, capacities.first(), "a stream's first read asks for the small initial buffer")
                    // How far it grows depends on how many bytes are queued when each read runs, which the scheduler
                    // decides; ReadChunkTests pins the sizing rule itself.
                    assertTrue(capacities.max() > 2_048, "a stream that fills its buffer asks for a larger one")
                }
            }
        }

    private companion object {
        const val TOTAL = 16 * 1024 * 1024
        const val CHUNK = 65_536
    }
}
