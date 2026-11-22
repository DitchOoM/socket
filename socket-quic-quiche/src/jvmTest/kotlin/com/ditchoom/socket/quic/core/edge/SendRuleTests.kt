package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.sched.HostKernel
import com.ditchoom.socket.quic.core.sched.SendRule
import com.ditchoom.socket.quic.core.sched.callerLanesOf
import com.ditchoom.socket.quic.core.sched.kernelOfOsName
import com.ditchoom.socket.quic.core.sched.laneCount
import com.ditchoom.socket.quic.core.sched.sendRuleOf
import com.ditchoom.socket.quic.core.sched.sendRuleOfThisRuntime
import com.ditchoom.socket.quic.core.sched.size
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Which threads send: under [SendRule.ByLoop] no caller enters a connection or sends, however its work reaches the
 * connection, and every datagram leaves from a loop thread; under [SendRule.InPlace] the same calls send in place.
 */
class SendRuleTests {
    @Test
    fun darwinHandsEverySendToTheLoopAndEveryOtherKernelSendsInPlace() {
        assertIs<SendRule.ByLoop>(sendRuleOf(HostKernel.Darwin))
        assertIs<SendRule.InPlace>(sendRuleOf(HostKernel.Linux))
        assertIs<SendRule.InPlace>(sendRuleOf(HostKernel.Windows))
        assertIs<SendRule.InPlace>(sendRuleOf(HostKernel.Other))
        assertEquals(HostKernel.Darwin, kernelOfOsName("Mac OS X"))
        assertEquals(HostKernel.Darwin, kernelOfOsName("Darwin"))
        assertEquals(HostKernel.Linux, kernelOfOsName("Linux"))
        assertEquals(HostKernel.Windows, kernelOfOsName("Windows 11"))
        assertEquals(HostKernel.Other, kernelOfOsName("FreeBSD"))
        assertEquals(sendRuleOf(kernelOfOsName(System.getProperty("os.name"))), sendRuleOfThisRuntime)
    }

    @Test
    fun aSocketUnderByLoopLendsNoLane() {
        val make = { i: Int -> CallerSend(i, SendBatch(nativeBytes(1_500), BatchLimits(1, 1_500), lane = i + 1)) }
        assertIs<CallerLanes.None>(callerLanesOf(SendRule.ByLoop.laneCount(8), make))
        assertEquals(8, callerLanesOf(SendRule.InPlace.laneCount(8), make).size)
    }

    @Test
    fun underByLoopNoCallerSendsEvenAWriterThatBlocksAndRetries() =
        runBlocking {
            skipOnMissingNativeLib(SendRuleTests::class) {
                val run = bulk(SendRule.ByLoop, STREAMS_SMALL, BYTES_SMALL)
                assertEquals(0L, run.clientEntered, "callers entered the client connection: $run")
                assertEquals(0L, run.clientInPlace, "callers sent in place on the client: $run")
                assertEquals(0L, run.serverInPlace, "callers sent in place on the server: $run")
                assertEquals(emptyMap(), run.offLoop(), "datagrams sent off a loop thread: $run")
            }
        }

    @Test
    fun underInPlaceTheSameWritersSendInPlace() =
        runBlocking {
            skipOnMissingNativeLib(SendRuleTests::class) {
                val run = bulk(SendRule.InPlace, STREAMS_SMALL, BYTES_SMALL)
                assertTrue(run.clientEntered > 0 && run.clientInPlace > 0, "no caller sent in place on the client: $run")
                assertTrue(run.offLoop().isNotEmpty(), "every datagram left from a loop thread: $run")
            }
        }

    @Test
    fun underByLoopEightBulkStreamsAllFinishWithNoCallerSending() =
        runBlocking {
            skipOnMissingNativeLib(SendRuleTests::class) {
                val run = bulk(SendRule.ByLoop, STREAMS_BULK, BYTES_BULK)
                assertEquals(STREAMS_BULK, run.finishedMillis.size, "a stream did not finish: $run")
                assertEquals(0L, run.clientEntered, "callers entered the client connection: $run")
                assertEquals(0L, run.clientInPlace, "callers sent in place on the client: $run")
                assertEquals(0L, run.serverInPlace, "callers sent in place on the server: $run")
                assertEquals(emptyMap(), run.offLoop(), "datagrams sent off a loop thread: $run")
            }
        }

    /** What one run of [bulk] saw. */
    private class Run(
        val clientEntered: Long,
        val clientInPlace: Long,
        val serverInPlace: Long,
        val sendsByThread: Map<String, Long>,
        val finishedMillis: List<Long>,
    ) {
        fun offLoop(): Map<String, Long> = sendsByThread.filterKeys { !it.startsWith(LOOP_THREAD) && !it.startsWith(SERVER_THREAD) }

        override fun toString() =
            "client entries=$clientEntered client in-place sends=$clientInPlace server in-place sends=$serverInPlace " +
                "sends by thread=$sendsByThread finished after=$finishedMillis ms"
    }

    /**
     * Echoes [bytes] on each of [streams] streams at once, client and core server both under [rule], with every writer
     * and reader on [Dispatchers.Default]. A write larger than the stream's window blocks and is retried.
     */
    private suspend fun bulk(
        rule: SendRule,
        streams: Int,
        bytes: Int,
    ): Run {
        val loops = LoopSet(1, sends = rule)
        val serverIo = arrayOfNulls<RecordingIo>(1)
        try {
            var run: Run? = null
            withCoreEchoServer(sends = rule, wrapIo = { RecordingIo(it).also { io -> serverIo[0] = io } }) { server ->
                val clientIo = arrayOfNulls<RecordingIo>(1)
                val client = connectCore(server.port, loops = loops) { RecordingIo(it).also { io -> clientIo[0] = io } }
                val started = System.nanoTime()
                val finished =
                    withContext(Dispatchers.Default) {
                        (0 until streams)
                            .map { n ->
                                async {
                                    val payload = Random(n).nextBytes(bytes)
                                    assertContentEquals(payload, echoOver(client.connection.openStream(), payload), "stream $n")
                                    (System.nanoTime() - started) / 1_000_000
                                }
                            }.awaitAll()
                    }
                run =
                    Run(
                        clientEntered = client.conn.counts.enteredInPlace,
                        clientInPlace = client.loop.callerCounts.sendsInPlace,
                        serverInPlace = server.loop.callerCounts.sendsInPlace,
                        sendsByThread =
                            (clientIo[0]!!.sendsByThread().entries + serverIo[0]!!.sendsByThread().entries)
                                .groupBy({ it.key }, { it.value })
                                .mapValues { it.value.sum() },
                        finishedMillis = finished,
                    )
                client.connection.close(QuicError.NoError)
            }
            return run!!.also { println("[core send rule] $rule: $it") }
        } finally {
            loops.close()
        }
    }

    private companion object {
        const val STREAMS_SMALL = 2
        const val BYTES_SMALL = 2 shl 20
        const val STREAMS_BULK = 8
        const val BYTES_BULK = 4 shl 20
    }
}
