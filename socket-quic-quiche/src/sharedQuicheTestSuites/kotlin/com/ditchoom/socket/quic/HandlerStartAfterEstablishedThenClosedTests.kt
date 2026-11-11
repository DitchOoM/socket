package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A server connection whose handshake completed runs its handler even when it is already closed by
 * the time the accept loop looks.
 *
 * A client whose block returns at once completes the handshake and closes in the same breath. The
 * driver publishes Established and then Closed; [QuicheDriver.state] conflates, so a handler-start
 * collector dispatched after both sees only Closed, which says nothing of whether the handshake
 * finished. Reading it as "closed before serving" skipped the handler for a connection that had been
 * established, and `rapidBindConnectCloseCyclesAreClean` waited 5 s for a handler that never ran.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandlerStartAfterEstablishedThenClosedTests {
    @Test
    fun aHandshakeThatCompletedBeforeTheCloseStartsTheHandlerEvenIfOnlyClosedIsObserved() =
        runTest {
            val api = StubQuicheApi().apply { established = true }
            val driver = serverDriver(api)
            // A dispatcher nobody runs yet: the collector is parked, as one dispatched late is.
            val late = QueueDispatcher()
            val start = CoroutineScope(late).async { driver.awaitHandlerStart() }
            late.drain() // subscribes, sees Handshaking

            driver.start(this)
            try {
                runCurrent()
                assertIs<QuicConnectionState.Established>(driver.state.value, "the handshake never completed")
                api.closed = true // the peer's close, in a later wake than the handshake's
                driver.commands.send(QuicheCmd.OpenStream(CompletableDeferred()))
                runCurrent()
                assertIs<QuicConnectionState.Closed>(driver.state.value)

                // Now the late collector runs: Established was replaced by Closed before it resumed.
                late.drain()
                assertEquals(HandlerStart.Now, start.await(), "an established connection's handler was skipped")
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun aConnectionThatClosedBeforeTheHandshakeCompletedStillStartsNoHandler() =
        runTest {
            val api = StubQuicheApi().apply { established = false }
            val driver = serverDriver(api)
            val start = backgroundScope.async { driver.awaitHandlerStart() }
            driver.start(this)
            try {
                runCurrent()
                api.closed = true
                driver.commands.send(QuicheCmd.OpenStream(CompletableDeferred()))
                runCurrent()
                assertEquals(HandlerStart.Never, start.await())
            } finally {
                driver.destroy()
            }
        }

    /** Runs what it was handed only when [drain] says so. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queued.addLast(block)
        }

        fun drain() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private fun serverDriver(api: QuicheApi): QuicheDriver =
        QuicheDriver(
            migration = MigrationCapability.ServerConnection,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = BufferFactory.deterministic(),
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            role = QuicRole.Server,
            ingress = DatagramIngress.ExternalPump,
            driverContext = EmptyCoroutineContext,
        )
}
