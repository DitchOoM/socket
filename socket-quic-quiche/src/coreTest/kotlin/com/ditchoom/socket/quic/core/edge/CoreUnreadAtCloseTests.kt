package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicUnreadAtClose
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.awaitCancellation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A core connection's [com.ditchoom.socket.quic.QuicScope.unreadAtClose] tracks the connection: open while it is,
 * and settled once it ends, so `runUntilEnded` cancels a block on a connection the peer closed promptly instead of
 * waiting out its linger.
 */
class CoreUnreadAtCloseTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aPeerCloseEndsTheBlockWellUnderTheLinger() =
        coreTest("CoreUnreadAtCloseTests.aPeerCloseEndsTheBlockWellUnderTheLinger") {
            withCoreServer(onConnection = { connection -> connection.close(QuicError.NoError) }) { server ->
                val client = connectCore(server.port, loops = loops)
                val connection = client.connection
                // The close may land before the connect returns: then the connection is handed over ended, all read.
                val unread = connection.unreadAtClose.value
                assertTrue(
                    unread is QuicUnreadAtClose.ConnectionOpen || connection.end() is EdgeEnd.Ended,
                    "nothing is unread at a close that has not happened: $unread",
                )
                val began = TimeSource.Monotonic.markNow()
                val closed = assertFailsWith<QuicCloseException> { connection.runUntilEnded(LINGER) { awaitCancellation() } }
                val took = began.elapsedNow()
                assertIs<QuicCloseReason.ByPeer>(closed.closeReason)
                assertTrue(took < LINGER / 4, "the block ended $took after the peer closed; the linger is $LINGER")
                assertEquals<QuicUnreadAtClose>(QuicUnreadAtClose.AllRead, connection.unreadAtClose.value)
            }
        }

    @Test
    fun aReplyInTheFlightOfThePeersCloseIsReadAndTheBlockStillEndsPromptly() =
        coreTest("CoreUnreadAtCloseTests.aReplyInTheFlightOfThePeersCloseIsReadAndTheBlockStillEndsPromptly") {
            withCoreServer(onConnection = { connection ->
                val stream = connection.openStream()
                stream.writeFully(
                    BufferFactory
                        .network()
                        .allocate(1)
                        .apply { writeByte(REPLY) }
                        .also { it.resetForRead() },
                    10.seconds,
                )
                connection.close(QuicError.NoError)
            }) { server ->
                val client = connectCore(server.port, loops = loops, options = acceptingClientOptions)
                val connection = client.connection
                var replied = -1
                val began = TimeSource.Monotonic.markNow()
                assertFailsWith<QuicCloseException> {
                    connection.runUntilEnded(LINGER) {
                        val stream = acceptStream()
                        stream.read(10.seconds) { replied = it.readByte().toInt() }.also { assertIs<ScopedReadResult.Data<*>>(it) }
                        awaitCancellation()
                    }
                }
                assertEquals(REPLY.toInt(), replied, "the reply sent with the close was read")
                assertTrue(began.elapsedNow() < LINGER / 4, "the block ended ${began.elapsedNow()} after the peer closed")
                assertEquals<QuicUnreadAtClose>(QuicUnreadAtClose.AllRead, connection.unreadAtClose.value)
            }
        }

    private companion object {
        val LINGER = 20.seconds
        const val REPLY: Byte = 7
    }
}
