package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.testing.LoopScheduling
import com.ditchoom.socket.quic.core.udp.buffers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The JVM-only rows of the core client against the core server: 1,024 connections and a wildcard server. */
class CoreServerEchoTests {
    @get:org.junit.Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    /** Client loops of this class's own, so connections it leaves ending never share a thread with another test's. */
    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aThousandAndTwentyFourConcurrentConnectionsEchoOnOneServerLoop() = concurrentEchoes(1_024)

    private fun concurrentEchoes(connections: Int) =
        runBlocking {
            withCoreEchoServer(ServerConfig(admission = AdmissionBounds(perPass = 256, inProgress = 2_048))) { server ->
                val connectedAfter = ConcurrentLinkedQueue<Long>()
                val scheduled = LoopScheduling.snapshot()
                val process = LoopScheduling.snapshotAll()
                val collected = LoopScheduling.gcStats()
                val clockBefore = LoopScheduling.clockReport()
                val started = System.nanoTime()
                try {
                    val echoed =
                        (0 until connections)
                            .map { n ->
                                // Default: a client only suspends, and IO would wake a worker per client.
                                async(Dispatchers.Default) {
                                    val client = connectCore(server.port, loops = loops)
                                    connectedAfter.add((System.nanoTime() - started) / NANOS_PER_MILLI)
                                    try {
                                        val payload = Random(n).nextBytes(1_024)
                                        payload to echoOver(client.connection.openStream(), payload)
                                    } finally {
                                        client.connection.close(QuicError.NoError)
                                    }
                                }
                            }.awaitAll()
                    echoed.forEachIndexed { n, (sent, back) -> assertContentEquals(sent, back, "connection $n") }
                    assertEquals(connections.toLong(), server.counts.accepted)
                    // Passing runs too, so the per-connection cost compares across runtimes (CI greps CORE-CPU).
                    println(
                        "CORE-CPU $connections echo connections in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms: " +
                            "${LoopScheduling.cpuPerConnection(process, connections)}; " +
                            "clock before: $clockBefore, after: ${LoopScheduling.clockReport()}; " +
                            "${LoopScheduling.gcSince(collected)}; server socket ${server.socket.buffers.receiveBytes} B " +
                            "receive buffer, drops=${server.socket.drops()}, " +
                            "${server.loop.counts.datagramsIn} in over ${server.loop.counts.passes} passes; " +
                            "server loop ${timing(server.loop.counts)}; loops ${LoopScheduling.since(scheduled)}",
                    )
                } catch (failed: Throwable) {
                    // What the server and its socket saw, so the next occurrence names where the handshakes went.
                    val times = connectedAfter.sorted()
                    val counts = server.counts
                    val clientLoops = loops.countsOfStarted().joinToString { timing(it) }
                    throw AssertionError(
                        "$connections connections, ${times.size} connected after ${times.lastOrNull()} ms (median ${times.getOrNull(
                            times.size / 2,
                        )}): " +
                            "accepted=${counts.accepted} routed=${counts.routed} droppedAdmission=${counts.droppedAdmission} " +
                            "droppedUnknown=${counts.droppedUnknown} loopPasses=${server.loop.counts.passes} " +
                            "datagramsIn=${server.loop.counts.datagramsIn} datagramsOut=${server.loop.counts.datagramsOut} " +
                            "receiveFailures=${server.loop.counts.receiveFailures} sendRefusals=${server.loop.counts.sendRefusals} " +
                            "socket=${server.socket.buffers.receiveBytes} B receive buffer, drops=${server.socket.drops()}; " +
                            "heap ${heapMebibytes()}; " +
                            "server loop ${timing(server.loop.counts)}; client loops $clientLoops; " +
                            "scheduling ${LoopScheduling.since(scheduled)}; " +
                            "process ${LoopScheduling.cpuPerConnection(process, connections)}; " +
                            "clock before: $clockBefore, after: ${LoopScheduling.clockReport()}; " +
                            "${LoopScheduling.gcSince(collected)}; " +
                            "a route probe (open, bind, connect, close) takes ${probeMillis(server.port)} ms on this machine",
                        failed,
                    )
                }
            }
        }

    @Test
    fun aWildcardServerAnswersIPv4AndIPv6Clients() =
        runBlocking {
            withCoreEchoServer(bind = wildcardV4()) { server ->
                val hosts = listOf("127.0.0.1") + if (hasIPv6Loopback()) listOf("::1") else emptyList()
                for (host in hosts) {
                    val client = connectCore(server.port, host = host, loops = loops)
                    try {
                        val payload = Random(host.length).nextBytes(10_000)
                        assertContentEquals(payload, echoOver(client.connection.openStream(), payload), "echo over $host")
                    } finally {
                        client.connection.close(QuicError.NoError)
                    }
                }
                assertEquals(hosts.size.toLong(), server.counts.accepted)
            }
        }

    /**
     * What one route probe costs here, in milliseconds: every client connection takes the process-wide probe gate, so
     * this is the pace the connections of a herd start at, at best.
     */
    private fun probeMillis(port: Int): Double {
        val probes = 20
        val began = System.nanoTime()
        repeat(probes) {
            DatagramChannel.open().use { probe ->
                probe.bind(InetSocketAddress("0.0.0.0", 0))
                probe.connect(InetSocketAddress("127.0.0.1", port))
                probe.localAddress
            }
        }
        return (System.nanoTime() - began) / probes / NANOS_PER_MILLI.toDouble()
    }

    /** The Java heap in use against its limit: an allocation at the limit waits for a collection, which stalls a loop. */
    private fun heapMebibytes(): String {
        val runtime = Runtime.getRuntime()
        return "${(runtime.totalMemory() - runtime.freeMemory()) shr 20} of ${runtime.maxMemory() shr 20} MiB"
    }

    /** How a loop spent its time: passes against waits, and its longest pass. */
    private fun timing(counts: LoopCounts): String =
        "[${counts.passes} passes took ${counts.passNanos / NANOS_PER_MILLI} ms, " +
            "longest ${counts.longestPassNanos / NANOS_PER_MILLI} ms; " +
            "${counts.waits} waits took ${counts.waitNanos / NANOS_PER_MILLI} ms; ${counts.datagramsIn} in, ${counts.datagramsOut} out]"

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }

    private fun hasIPv6Loopback(): Boolean =
        runCatching {
            DatagramChannel.open(StandardProtocolFamily.INET6).use { it.bind(InetSocketAddress("::1", 0)) }
        }.isSuccess
}
