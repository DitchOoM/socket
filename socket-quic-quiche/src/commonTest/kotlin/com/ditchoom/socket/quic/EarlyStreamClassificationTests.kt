@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A stream is "early" (0-RTT) only if it was readable before the TLS handshake completed, judged by what
 * quiche says when the driver looks at the stream, not by the state the driver last published.
 *
 * The driver publishes [QuicConnectionState.Established] in `updateState`, the last step of a turn, after
 * `discoverNewStreams`. A turn that feeds quiche the datagram completing the handshake AND a datagram
 * carrying 1-RTT stream data (one coalesced datagram, or two queued behind each other and run before one
 * flush) therefore looked at the stream while the published state still read Handshaking, and the server
 * reported ordinary 1-RTT data as early data, on a connection that had declined 0-RTT
 * (`resumingWithoutEarlyDataSkipsTheCertificateButSendsNothingEarly`: `earlyStreamData=Received` beside
 * `DeclinedByPeer`). Each test states the order of quiche's answers, so no scheduler or machine load decides it.
 */
class EarlyStreamClassificationTests {
    private val bufferFactory = BufferFactory.deterministic()

    private fun serverDriver(
        stub: StubQuicheApi,
        clock: SimClock,
    ): QuicheDriver =
        QuicheDriver(
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = stub,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            role = QuicRole.Server,
            ingress = DatagramIngress.ExternalPump,
            clock = clock,
            driverContext = EmptyCoroutineContext,
        )

    private fun datagram() =
        QuicheCmd.RecvPacket(
            bufferFactory.allocate(PACKET_LEN),
            PACKET_LEN,
            PacketSource.FromServerSocket(QuicheRecvInfo(1L)) {
            },
        )

    @Test
    fun streamDataInTheDatagramThatCompletesTheHandshakeIsNotEarly() =
        runTest {
            val stub = StubQuicheApi()
            stub.established = false
            val driver = serverDriver(stub, SimClock(testScheduler))
            driver.start(CoroutineScope(coroutineContext + Job()))
            runCurrent()
            // One datagram: the client's Finished coalesced with its first 1-RTT stream data.
            stub.onConnRecv = {
                stub.established = true
                stub.readableStreams.addLast(0L)
            }
            driver.commands.trySend(datagram())
            advanceUntilIdle()
            driver.destroy()

            assertEquals(
                QuicheDriver.EarlyStreamData.None,
                driver.earlyStreamData,
                "quiche had completed the handshake when the stream became readable: that is 1-RTT data, not 0-RTT",
            )
        }

    @Test
    fun streamDataInTheNextDatagramOfTheSameTurnIsNotEarly() =
        runTest {
            val stub = StubQuicheApi()
            stub.established = false
            val driver = serverDriver(stub, SimClock(testScheduler))
            driver.start(CoroutineScope(coroutineContext + Job()))
            runCurrent()
            var fed = 0
            stub.onConnRecv = {
                if (++fed == 1) stub.established = true else stub.readableStreams.addLast(0L)
            }
            // Both are queued before the loop runs: it feeds quiche both, then takes one turn's worth of signalling.
            driver.commands.trySend(datagram())
            driver.commands.trySend(datagram())
            advanceUntilIdle()
            driver.destroy()

            assertEquals(2, fed, "the loop fed quiche both datagrams")
            assertEquals(
                QuicheDriver.EarlyStreamData.None,
                driver.earlyStreamData,
                "the second datagram arrived after the handshake completed: its stream is 1-RTT, not 0-RTT",
            )
        }

    @Test
    fun streamDataReadableBeforeTheHandshakeCompletesIsEarly() =
        runTest {
            val stub = StubQuicheApi()
            stub.established = false
            val driver = serverDriver(stub, SimClock(testScheduler))
            driver.start(CoroutineScope(coroutineContext + Job()))
            runCurrent()
            stub.onConnRecv = { stub.readableStreams.addLast(0L) }
            driver.commands.trySend(datagram())
            advanceUntilIdle()
            driver.destroy()

            assertEquals(
                QuicheDriver.EarlyStreamData.Received(0L),
                driver.earlyStreamData,
                "a stream readable while quiche has not completed the handshake is 0-RTT data",
            )
        }

    private companion object {
        const val PACKET_LEN = 64
    }
}
