package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A connect cancelled at any instant unwinds promptly, whatever the socket and the loop were doing when the cancel landed:
 * toward a peer that never answers, where cancellation is the only end, and toward a server that answers, where the
 * handshake may complete first. Where datagrams ride Network.framework the cancel can land in its connect.
 */
class CoreCancelledHandshakeUnwindTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /** Connects to [port], cancels after the jitter, and answers whether the connect unwound within [UNWIND]. */
    private suspend fun cancelledAfter(
        port: Int,
        yields: Int,
        micros: Long,
    ): Boolean {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val work =
            scope.async {
                val client = connectCore(port, loops = loops)
                client.connection.close(QuicError.NoError)
            }
        repeat(yields) { yield() }
        if (micros > 0) delay(micros.microseconds)
        work.cancel()
        return withTimeoutOrNull(UNWIND) { work.join() } != null
    }

    @Test
    fun aConnectToAPeerThatNeverAnswersUnwindsWhenCancelledAtAnyInstant() =
        runBlocking {
            val silent = UdpSocket.bind("127.0.0.1", 0)
            try {
                val random = Random(7)
                val hung =
                    (0 until ITERATIONS).count {
                        !cancelledAfter(silent.localAddress.port, random.nextInt(0, 300), random.nextLong(0, 15_000))
                    }
                assertEquals(0, hung, "cancelled connects that did not unwind within $UNWIND, of $ITERATIONS")
            } finally {
                silent.close()
            }
        }

    @Test
    fun aConnectToAServerCancelledAtAnyInstantUnwindsAndTheServerServesTheNext() =
        runBlocking {
            withCoreEchoServer { server ->
                val random = Random(11)
                val hung =
                    (0 until ITERATIONS).count {
                        !cancelledAfter(server.port, random.nextInt(0, 300), random.nextLong(0, 15_000))
                    }
                assertEquals(0, hung, "cancelled connects that did not unwind within $UNWIND, of $ITERATIONS")
                val client = connectCore(server.port, loops = loops)
                try {
                    val payload = Random(1).nextBytes(512)
                    assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    private companion object {
        const val ITERATIONS = 60
        val UNWIND = 5.seconds
    }
}
