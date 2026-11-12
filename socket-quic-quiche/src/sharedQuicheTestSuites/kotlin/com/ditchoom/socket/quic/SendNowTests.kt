package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A send the socket takes at once is made without the bounded wait, and a send that has to wait still takes
 * it: [UdpChannel.sendNow] answers first and only a `null` goes on to [UdpChannel.send] under the stall bound.
 */
class SendNowTests {
    private class CountingChannel(
        private val now: SendOutcome?,
    ) : UdpChannel,
        ImmediateUdpSend {
        @Volatile
        var nowCount = 0

        @Volatile
        var sendCount = 0

        override suspend fun receive(buffer: PlatformBuffer): Int = awaitCancellation()

        override fun sendNow(
            buffer: PlatformBuffer,
            len: Int,
            target: SendTarget,
        ): SendOutcome? {
            nowCount++
            return now
        }

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            target: SendTarget,
        ): SendOutcome {
            sendCount++
            return SendOutcome.Sent
        }

        override fun close() = Unit
    }

    private suspend fun flushOnce(channel: CountingChannel) {
        val api = StubQuicheApi()
        val driver =
            QuicheDriver(
                migration = MigrationCapability.BackendCannotMigrate,
                rawApi = api,
                conn = QuicheConn(1L),
                bufferFactory = BufferFactory.deterministic(),
                recvInfo = QuicheRecvInfo(1L),
                sendInfo = QuicheSendInfo(1L),
                udpChannel = channel,
                role = QuicRole.Client,
                ingress = DatagramIngress.ExternalPump,
            )
        kotlinx.coroutines.coroutineScope {
            driver.start(this)
            try {
                api.connSendOnce = 1200
                val turn = CompletableDeferred<QuicStatsSnapshot>()
                driver.commands.send(QuicheCmd.Stats(turn))
                withTimeout(5.seconds) { turn.await() }
                withTimeout(5.seconds) { while (channel.nowCount == 0) yield() }
            } finally {
                driver.destroy()
            }
        }
    }

    @Test
    fun aSendTheSocketTakesAtOnceNeverReachesTheBoundedSend() =
        runQuicTest(timeout = 20.seconds) {
            val channel = CountingChannel(now = SendOutcome.Sent)
            flushOnce(channel)
            assertEquals(1, channel.nowCount)
            assertEquals(0, channel.sendCount, "a send that was taken at once went through the bounded wait as well")
        }

    @Test
    fun aSendThatWouldWaitIsMadeTheWaitingWay() =
        runQuicTest(timeout = 20.seconds) {
            val channel = CountingChannel(now = null)
            flushOnce(channel)
            assertEquals(1, channel.nowCount)
            assertEquals(1, channel.sendCount, "a send that would wait was dropped instead of made under the bound")
        }
}
