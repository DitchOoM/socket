package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A closed connection's identity is read from what the driver latched while the connection was alive —
 * never from the quiche connection that teardown has freed.
 *
 * The victim validates content rather than waiting for a crash: before `connFree` the quiche double
 * answers the identity reads with one length, after it with another, so an identity read off the freed
 * connection comes out as a different value even where the real library would have read freed memory
 * silently. It also counts those reads, which must be zero.
 */
class IdentityAfterCloseTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** [StubQuicheApi] whose identity accessors answer differently once the connection is freed. */
    private class FreedConnVictimApi(
        private val stub: StubQuicheApi,
    ) : QuicheApi by stub {
        @Volatile
        var freed = false
            private set

        @Volatile
        var readsAfterFree = 0
            private set

        override fun connFree(conn: QuicheConn) {
            freed = true
            stub.connFree(conn)
        }

        override fun connSourceId(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
        ): Int = identityRead(LIVE_SOURCE_ID_LEN)

        override fun connTraceId(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
        ): Int = identityRead(LIVE_TRACE_ID_LEN)

        private fun identityRead(liveLen: Int): Int {
            if (!freed) return liveLen
            readsAfterFree++
            return FREED_LEN
        }
    }

    @Test
    fun theCloseABlockIsCancelledWithNamesTheConnectionAsItWasBeforeItWasFreed() =
        runTest(timeout = 30.seconds) {
            val stub = StubQuicheApi()
            val api = FreedConnVictimApi(stub)
            val driver = createTestDriver(api)
            driver.start(this)
            val connJob = SupervisorJob()
            val connection =
                DriverQuicConnection(driver, bufferFactory, SocketAddress.ofLiteral("127.0.0.1", 4433), CoroutineScope(connJob))
            val outcome = CompletableDeferred<Throwable?>()
            launch {
                outcome.complete(runCatching { connection.runUntilClosed(linger = 1.hours) { awaitCancellation() } }.exceptionOrNull())
            }
            try {
                runCurrent()
                val live = connection.identity

                stub.closed = true
                driver.commands.trySend(QuicheCmd.Stats(CompletableDeferred()))
                runCurrent()

                assertTrue(api.freed, "teardown never freed the connection, so nothing here was tested")
                val close = assertIs<QuicCloseException>(outcome.getCompleted())
                val attribution = assertIs<QuicCloseAttribution.Attributed>(close.attribution)
                assertEquals(live, attribution.identity, "the close must name the connection as it was while alive")
                assertEquals(live, connection.identity, "a closed connection's identity must be the latched one")
                assertEquals(0, api.readsAfterFree, "an identity read reached the freed quiche connection")
            } finally {
                connJob.cancel()
                driver.commands.close()
            }
        }

    private fun createTestDriver(api: QuicheApi): QuicheDriver =
        QuicheDriver(
            // Test double: never exercises a path move.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            role = QuicRole.Client,
            ingress = DatagramIngress.ExternalPump,
            clock = RealDriverClock,
            driverContext = EmptyCoroutineContext,
        )

    private companion object {
        const val LIVE_SOURCE_ID_LEN = 8
        const val LIVE_TRACE_ID_LEN = 6
        const val FREED_LEN = 3
    }
}
