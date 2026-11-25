@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.core.testing.PerfGates
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the core client allocates per 64-byte datagram echo against a core server, split into the loop thread and the
 * caller's thread with the JVM's per-thread allocation counter after warm-up; the server's threads are not counted.
 * Receiving a datagram into a caller-parked receive, queueing none, and sending one allocate nothing on the loop: what
 * is left there is the connection's timer re-arms, 0 to 7 bytes per echo. The caller's thread pays for what its API
 * forces: the operation objects, the continuations and the buffer the receive hands back.
 */
class CoreDatagramAllocationTests {
    @Test
    fun aDatagramEchoAllocatesNothingOnTheLoop() =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(CoreDatagramAllocationTests::class) {
                    val on = coreTestOptions.copy(datagrams = DatagramOptions())
                    withCoreServer(options = on, onConnection = { echoDatagrams(it.datagramChannel()) }) { server ->
                        val client = connectCore(server.port, factory = BufferFactory.network(), options = on)
                        val dispatcher = ArrayQueueDispatcher(CALLER_THREAD)
                        val done = CompletableFuture<kotlin.Result<Pair<Double, Double>>>()
                        suspend { measure(client, client.connection.datagramChannel()) }
                            .startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
                        val (loop, caller) = done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow()
                        println("[core datagram allocation] loop %.1f B/echo, caller %.1f B/echo".format(loop, caller))
                        client.connection.close(QuicError.NoError)
                        dispatcher.close()
                        val loopGate =
                            when (netStackOfThisRuntime) {
                                is NetStack.Native -> LOOP_GATE
                                is NetStack.Jdk -> JDK_LOOP_GATE
                            }
                        val owner = CoreDatagramAllocationTests::class.java
                        PerfGates.record(owner, "datagram-echo", "loop-bytes", loop, loopGate.toDouble(), "B-per-echo")
                        PerfGates.record(owner, "datagram-echo", "caller-bytes", caller, CALLER_GATE.toDouble(), "B-per-echo")
                        assertTrue(loop <= loopGate, "loop thread: %.1f B/datagram echo (gate %d)".format(loop, loopGate))
                        assertTrue(caller <= CALLER_GATE, "caller thread: %.1f B/datagram echo (gate %d)".format(caller, CALLER_GATE))
                    }
                }
            }
        }

    /** Runs on the caller's thread: warms up, then counts the bytes the client's loop and this thread allocate per echo. */
    private suspend fun measure(
        client: CoreClient,
        channel: ConnectedDatagramChannel,
    ): Pair<Double, Double> {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val payload =
            BufferFactory.network().allocate(PAYLOAD).apply {
                repeat(PAYLOAD) { writeByte(it.toByte()) }
                resetForRead()
            }
        repeat(WARM_UP) { echo(channel, payload) }
        val loopId = threadId(client.loop.name)
        val callerId = Thread.currentThread().id
        val loopBefore = threads.getThreadAllocatedBytes(loopId)
        val callerBefore = threads.getThreadAllocatedBytes(callerId)
        repeat(MEASURED) { echo(channel, payload) }
        val caller = (threads.getThreadAllocatedBytes(callerId) - callerBefore).toDouble() / MEASURED
        val loop = (threads.getThreadAllocatedBytes(loopId) - loopBefore).toDouble() / MEASURED
        return loop to caller
    }

    private suspend fun echo(
        channel: ConnectedDatagramChannel,
        payload: PlatformBuffer,
    ) {
        payload.position(0)
        channel.send(payload)
        when (val result = channel.receive()) {
            is DatagramReadResult.Received -> result.datagram.payload.freeIfNeeded()
            is DatagramReadResult.Closed -> fail("the echo ended early: ${result.reason}")
        }
    }

    private suspend fun echoDatagrams(channel: ConnectedDatagramChannel) {
        while (true) {
            when (val result = channel.receive()) {
                is DatagramReadResult.Received -> {
                    val payload = result.datagram.payload
                    channel.send(payload)
                    payload.freeIfNeeded()
                }
                is DatagramReadResult.Closed -> return
            }
        }
    }

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    private companion object {
        const val CALLER_THREAD = "core-datagram-alloc-caller"
        const val LOOP_GATE = 8
        const val JDK_LOOP_GATE = 130
        const val CALLER_GATE = 1_500
        const val PAYLOAD = 64
        const val WARM_UP = 5_000
        const val MEASURED = 20_000
        const val TIMEOUT_SECONDS = 120L
    }
}
