package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionMemory
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.edge.EdgeEnd
import com.ditchoom.socket.quic.core.edge.ReceivedBytes
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.openStream
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A core client against a core server over a real socket, on every platform: a 1 MiB stream written as fast as flow
 * control allows and finished, the same bytes echoed back to the end, and the connection closed by the client and seen
 * closed by it; a stream opened after the close fails instead of waiting.
 */
class CoreClientServerTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aCoreClientEchoesOneMebibyteThroughACoreServerAndClosesItsConnection() =
        coreTest("CoreClientServerTests.aCoreClientEchoesOneMebibyteThroughACoreServerAndClosesItsConnection") {
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                val payload = Random(7).nextBytes(PAYLOAD)
                val stream = client.connection.openStream()
                val received = ReceivedBytes(PAYLOAD)
                coroutineScope {
                    val reader =
                        async { while (stream.read(10.seconds) { received.add(it) } is ScopedReadResult.Data<*>) Unit }
                    stream.writeFully(
                        BufferFactory.network().allocate(PAYLOAD).apply {
                            writeBytes(payload)
                            resetForRead()
                        },
                        10.seconds,
                    )
                    (stream as HalfCloseable).shutdownSend()
                    reader.await()
                }
                assertContentEquals(payload, received.toByteArray(), "the echo, read to the server's finish")
                client.connection.close(QuicError.NoError)
                assertIs<ConnEnd.ByLocal>((client.connection.end() as EdgeEnd.Ended).end, "the connection ended by our close")
                assertFailsWith<QuicCloseException> { withTimeout(5.seconds) { client.connection.openStream() } }
            }
        }

    @Test
    fun aSecondConnectToTheSameServerResumesWithNoTicketFromTheCaller() =
        coreTest("CoreClientServerTests.aSecondConnectToTheSameServerResumesWithNoTicketFromTheCaller") {
            withCoreEchoServer { server ->
                val options = coreTestOptions.copy(sessionStore = QuicSessionStore.InMemory(QuicSessionMemory()))
                val first = connectCore(server.port, loops = loops, options = options)
                assertEquals(QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered), first.connection.resumption)
                withTimeout(10.seconds) { first.connection.sessionTicket.first { it is QuicSessionTicketState.Issued } }
                first.connection.close(QuicError.NoError)
                val second = connectCore(server.port, loops = loops, options = options)
                assertIs<QuicResumptionOutcome.Resumed>(second.connection.resumption, "the reconnect resumed the first session")
                second.connection.close(QuicError.NoError)
            }
        }

    private companion object {
        const val PAYLOAD = 1 shl 20
    }
}
