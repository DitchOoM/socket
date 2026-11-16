@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.CoreClient
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.withEchoServer
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Client CPU per echo for 64 connections echoing against an in-process server on loopback, for K shared loops and for
 * one loop per connection. Run with `CORE_CLIENT_LOOP_MEASURE=1`; it takes over a minute and prints a table.
 *
 * Client CPU is the CPU time of the client's loop threads plus its caller threads; the server's threads are not counted.
 */
class SharedClientLoopMeasurement {
    @Test
    fun cpuPerEchoByNumberOfLoops() {
        if (System.getenv("CORE_CLIENT_LOOP_MEASURE") == null) return
        runBlocking {
            skipOnMissingNativeLib(SharedClientLoopMeasurement::class) {
                withEchoServer { port ->
                    println("[client loops] $CONNECTIONS connections, $PAYLOAD B echoes, ${MEASURED_SECONDS} s each")
                    println(
                        "[client loops] loops | echoes/s | loop-thread CPU µs/op (kernel %) | caller CPU µs/op | total µs/op | passes/datagram | datagrams/wait",
                    )
                    for (k in listOf(1, 2, 4, 8, CONNECTIONS)) measure(port, k)
                }
            }
        }
    }

    private suspend fun measure(
        port: Int,
        k: Int,
    ) {
        val loops = ClientLoopSet(k)
        val callers = Executors.newFixedThreadPool(CALLER_THREADS) { Thread(it, "client-caller-$k-${System.nanoTime()}") }
        val dispatcher = callers.asCoroutineDispatcher()
        try {
            val clients = withContext(Dispatchers.IO) { List(CONNECTIONS) { connect(port, loops) } }
            val distinct = clients.map { it.loop }.distinct()
            val echoes = AtomicLong()
            val running =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val drivers =
                clients.map { client ->
                    kotlinx.coroutines.CoroutineScope(dispatcher).async {
                        val stream = client.connection.openStream()
                        val payload =
                            BufferFactory.Default.allocate(PAYLOAD).apply {
                                for (i in 0 until PAYLOAD) writeByte(i.toByte())
                                resetForRead()
                            }
                        while (running.get()) {
                            roundTrip(stream, payload)
                            echoes.incrementAndGet()
                        }
                    }
                }
            delay(WARM_UP_SECONDS.seconds)
            val threads = ManagementFactory.getThreadMXBean()
            val loopIds = distinct.map { threadId(it.name) }
            val callerIds =
                Thread
                    .getAllStackTraces()
                    .keys
                    .filter { it.name.startsWith("client-caller-$k-") }
                    .map { it.id }

            fun cpu(ids: List<Long>) = ids.sumOf { threads.getThreadCpuTime(it) }

            fun user(ids: List<Long>) = ids.sumOf { threads.getThreadUserTime(it) }
            val before =
                Snapshot(
                    echoes.get(),
                    cpu(loopIds),
                    user(loopIds),
                    cpu(callerIds),
                    distinct.sumOf {
                        it.counts.passes
                    },
                    distinct.sumOf { it.counts.datagramsIn },
                    distinct.sumOf { it.counts.waits },
                )
            delay(MEASURED_SECONDS.seconds)
            val after =
                Snapshot(
                    echoes.get(),
                    cpu(loopIds),
                    user(loopIds),
                    cpu(callerIds),
                    distinct.sumOf {
                        it.counts.passes
                    },
                    distinct.sumOf { it.counts.datagramsIn },
                    distinct.sumOf { it.counts.waits },
                )
            running.set(false)
            drivers.awaitAll()
            val ops = (after.echoes - before.echoes).toDouble()
            val loopCpu = (after.loopCpu - before.loopCpu).toDouble() / 1_000.0
            val loopUser = (after.loopUser - before.loopUser).toDouble() / 1_000.0
            val callerCpu = (after.callerCpu - before.callerCpu).toDouble() / 1_000.0
            val datagrams = (after.datagramsIn - before.datagramsIn).toDouble()
            println(
                "[client loops] %5d | %9.0f | %6.1f (%2.0f%% kernel) | %6.1f | %6.1f | %5.2f | %5.2f".format(
                    k,
                    ops / MEASURED_SECONDS,
                    loopCpu / ops,
                    100.0 * (1.0 - loopUser / loopCpu),
                    callerCpu / ops,
                    (loopCpu + callerCpu) / ops,
                    (after.passes - before.passes) / datagrams,
                    datagrams / (after.waits - before.waits),
                ),
            )
            clients.forEach { it.connection.close(QuicError.NoError) }
        } finally {
            loops.close()
            callers.shutdownNow()
        }
    }

    private class Snapshot(
        val echoes: Long,
        val loopCpu: Long,
        val loopUser: Long,
        val callerCpu: Long,
        val passes: Long,
        val datagramsIn: Long,
        val waits: Long,
    )

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    private suspend fun connect(
        port: Int,
        loops: ClientLoopSet,
    ): CoreClient =
        coreConnect(
            host = "127.0.0.1",
            port = port,
            serverName = "localhost",
            options = coreTestOptions,
            factory = BufferFactory.Default,
            api = loadQuicheApi(),
            timeout = 20.seconds,
            readPolicy = ReadPolicy.Bounded(20.seconds),
            writePolicy = WritePolicy.Bounded(20.seconds),
            loops = loops,
        )

    private suspend fun roundTrip(
        stream: QuicByteStream,
        payload: PlatformBuffer,
    ) {
        payload.position(0)
        stream.write(payload, 10.seconds)
        var received = 0
        while (received < payload.limit()) {
            val result = stream.read(10.seconds)
            check(result is ReadResult.Data) { "the echo ended early: $result" }
            received += result.buffer.remaining()
            result.buffer.freeIfNeeded()
        }
    }

    private companion object {
        const val CONNECTIONS = 64
        const val PAYLOAD = 100
        const val CALLER_THREADS = 4
        const val WARM_UP_SECONDS = 2
        const val MEASURED_SECONDS = 10
    }
}
