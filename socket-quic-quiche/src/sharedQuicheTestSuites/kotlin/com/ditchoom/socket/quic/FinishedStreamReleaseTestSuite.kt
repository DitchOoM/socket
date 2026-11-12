package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A driver holds state for the streams of a connection only while they can still be used.
 *
 * One connection carries [STREAMS] request/response streams, one at a time: the client opens a stream,
 * writes a request and its FIN, the server reads it to the FIN and answers with a FIN of its own, the
 * client reads the answer to the FIN and closes. Afterwards both drivers hold a bounded number of streams
 * however many the connection has carried.
 */
abstract class FinishedStreamReleaseTestSuite {
    /** The platform's `libquiche` binding, certificate fixtures and `sockaddr` layout. */
    internal abstract fun simEnv(): MigrationSimEnv

    /** A JVM/Android member turns a missing native into a typed skip; Kotlin/Native members keep this default. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    @Test
    fun bothDriversHoldABoundedNumberOfStreamsAfterAConnectionCarriedManyFinishedOnes() =
        runTest {
            wrapTestBody {
                withMigrationSim(simEnv(), seed = 893_001L) {
                    repeat(STREAMS) { index -> requestResponse(index) }
                    assertEquals(
                        emptyList(),
                        driversHolding().filter { (_, held) -> held > HELD_AFTER_FINISH_BOUND },
                        "after $STREAMS finished streams, each driver may hold at most $HELD_AFTER_FINISH_BOUND of them",
                    )
                }
            }
        }

    /** How many streams each driver still holds, after a turn of each loop that follows every finish. */
    private suspend fun MigrationSimScope.driversHolding(): List<Pair<QuicRole, Int>> =
        listOf(clientDriver, serverDriver).map { driver ->
            // A command makes the loop take a turn, which is where finished streams are released.
            driver.stats()
            val snapshot = assertIs<Inspected.Read<DriverStallSnapshot>>(driver.stallSnapshot()).value
            snapshot.role to snapshot.streams.size
        }

    private suspend fun MigrationSimScope.requestResponse(index: Int) {
        val factory = BufferFactory.network()
        val stream = client.openStream()
        val request = factory.allocate(REQUEST_BYTES)
        request.writeString("request-$index".padEnd(REQUEST_BYTES, '.'), Charset.UTF8)
        request.resetForRead()
        stream.writeFullyAndShutdownSend(request, DEADLINE)
        request.freeNativeMemory()

        val accepted = server.acceptStream()
        accepted.readToEnd()
        val reply = factory.allocate(REQUEST_BYTES)
        reply.writeString("reply-$index".padEnd(REQUEST_BYTES, '.'), Charset.UTF8)
        reply.resetForRead()
        accepted.writeFullyAndShutdownSend(reply, DEADLINE)
        reply.freeNativeMemory()

        stream.readToEnd()
        stream.close()
        accepted.close()
    }

    private suspend fun QuicByteStream.readToEnd() {
        var bytes = 0
        while (true) {
            when (val read = read(DEADLINE)) {
                is ReadResult.Data -> {
                    bytes += read.buffer.remaining()
                    read.buffer.freeIfNeeded()
                }
                ReadResult.End -> break
                ReadResult.Reset -> fail("the stream was reset")
            }
        }
        assertTrue(bytes == REQUEST_BYTES, "the whole message arrives before the FIN")
    }

    private companion object {
        const val STREAMS = 300
        const val REQUEST_BYTES = 64
        const val HELD_AFTER_FINISH_BOUND = 4
        val DEADLINE = 30.seconds
    }
}
