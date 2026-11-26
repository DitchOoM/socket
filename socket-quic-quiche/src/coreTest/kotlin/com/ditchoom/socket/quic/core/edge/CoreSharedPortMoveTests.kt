@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.core.conn.MoveWanted
import com.ditchoom.socket.quic.core.conn.MoveWantedWork
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.serveQuicOnCore
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A move on a lent socket: the loop refuses it typed, since the caller's socket cannot be replaced, and the connection
 * stays on the socket it has, echoing; no second socket is opened beside it.
 */
class CoreSharedPortMoveTests {
    @Test
    fun aMoveOnALentSocketIsRefusedAndTheConnectionStaysOnIt() =
        coreTest("CoreSharedPortMoveTests.aMoveOnALentSocketIsRefusedAndTheConnectionStaysOnIt") {
            val served = serveQuicOnCore(0, LOOPBACK, platformTlsConfig(), coreTestOptions)
            val listener = assertIs<QuicServe.Serving<QuicListener>>(served).value
            val handlers = launch { listener.handleConnections { it.handleStreams(::sharedPortEcho) } }
            val shared = UdpSocket.bind(LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            val loops = LoopSet(1)
            try {
                val client =
                    coreConnect(
                        host = LOOPBACK,
                        port = listener.port,
                        serverName = "localhost",
                        options = coreTestOptions.copy(sessionStore = QuicSessionStore.NotKept),
                        factory = BufferFactory.network(),
                        api = platformQuicheApi(),
                        timeout = WAIT,
                        readPolicy = ReadPolicy.Bounded(WAIT),
                        writePolicy = WritePolicy.Bounded(WAIT),
                        loops = loops,
                        ride = ClientRide.Lent(LentChannel(shared.quic)),
                    )
                val connection = client.connection.public()
                assertSharedPortEchoes(connection, 0)
                client.conn.submit(MoveWantedWork(MoveWanted(PathKey(4, 0, 0L, LOOPBACK_V4), RttPrior.CONFIGURED)))
                val marks = awaitMark(client, "LentRefused")
                assertTrue("Moved" !in marks && "OpenRefused" !in marks, "no second socket was asked for beside the lent one: $marks")
                assertSharedPortEchoes(connection, 1)
                client.connection.closeNow(QuicError.NoError)
                val began = TimeSource.Monotonic.markNow()
                while (client.loop.connections.value() != 0L && began.elapsedNow() < WAIT) delay(10.milliseconds)
                assertEquals(0L, client.loop.connections.value(), "the ended connection left its loop, and let go of its socket")
                assertTrue(shared.quic.isOpen, "the lent socket outlives the connection")
            } finally {
                loops.close()
                shared.close()
                handlers.cancel()
                listener.close()
            }
        }

    /** The path marks of [client]'s flight once [mark] is among them, within [WAIT]. */
    private suspend fun awaitMark(
        client: CoreClient,
        mark: String,
    ): List<String> {
        val began = TimeSource.Monotonic.markNow()
        while (true) {
            val marks = ArrayList<String>()
            client.connection.dumpFlight(TraceSink { if (it is TraceEvent.PathState) marks += it.phase })
            if (mark in marks) return marks
            if (began.elapsedNow() > WAIT) throw AssertionError("no $mark within $WAIT: $marks")
            delay(20.milliseconds)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val LOOPBACK_V4 = 0x7F000001L
        val WAIT = 10.seconds
    }
}
