package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A core endpoint's datagrams start at the 1200 bytes every path carries, and grow only as probes are acknowledged. */
class CoreDatagramSizeTests {
    /** A link of 1280 bytes: a datagram over it is lost, as the path refuses it with DF set; the others go on. */
    private class SmallLink(
        private val real: DatagramBatchIo,
    ) : DatagramBatchIo by real {
        private val single = ConcurrentHashMap<Int, SendBatch>()

        override fun send(batch: SendBatch): SendBatchResult {
            val one =
                single.computeIfAbsent(batch.lane) { lane ->
                    val bytes = (NativeBytes.of(BufferFactory.Default.allocate(batch.limits.maxDatagramSize)) as Addressed.Native).bytes
                    SendBatch(bytes, BatchLimits(1, batch.limits.maxDatagramSize), lane)
                }
            for (n in 0 until batch.pending) {
                if (batch.lengthOf(n) > LINK) continue
                one.storage.copyFrom(batch.storage, batch.offsetOf(n), 0, batch.lengthOf(n))
                one.commit(batch.lengthOf(n), batch.targetOf(n))
                real.send(one)
                one.took(one.pending)
            }
            return SendBatchResult.AllTaken
        }
    }

    @Test
    fun theOptionsAreProbedWhereTheCoreSizesItsDatagrams() {
        val plain = QuicOptions(alpnProtocols = listOf("test"))
        assertEquals(false, plain.enablePmtuDiscovery)
        assertEquals(true, plain.sizedBy(DatagramSize.Probed).enablePmtuDiscovery)
        assertEquals(plain, plain.sizedBy(DatagramSize.Configured))
    }

    @Test
    fun aProbedClientEchoesOverALinkOf1280BytesAndAConfiguredOneDoesNot() =
        runBlocking {
            skipOnMissingNativeLib(CoreDatagramSizeTests::class) {
                assertTrue(echoesOverTheSmallLink(DatagramSize.Probed), "starting at 1200, the connection fits the link")
                assertFalse(echoesOverTheSmallLink(DatagramSize.Configured), "the 1350-byte datagrams never cross it")
            }
        }

    private suspend fun echoesOverTheSmallLink(size: DatagramSize): Boolean {
        var echoed = false
        withCoreEchoServer { server ->
            val client =
                try {
                    coreConnect(
                        host = "127.0.0.1",
                        port = server.port,
                        serverName = "localhost",
                        options = coreTestOptions,
                        factory = BufferFactory.Default,
                        api = loadQuicheApi(),
                        timeout = 5.seconds,
                        readPolicy = ReadPolicy.Bounded(5.seconds),
                        writePolicy = WritePolicy.Bounded(5.seconds),
                        wrapIo = { SmallLink(it) },
                        flight = TEST_FLIGHT,
                        datagramSize = size,
                    )
                } catch (failed: QuicCloseException) {
                    return@withCoreEchoServer
                }
            echoed =
                withTimeoutOrNull(5.seconds) { echoOver(client.connection.openStream(), Random(5).nextBytes(64 * 1024)) } != null
            client.connection.close(QuicError.NoError)
        }
        return echoed
    }

    private companion object {
        const val LINK = 1280
    }
}
