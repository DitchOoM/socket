package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The native memory an idle client connection holds from its factory, as the factory sized it: a pool's rounding counts.
 * A loop's own memory, which its connections share, is taken by one connection per loop first and not counted. On
 * Android a direct buffer lives on the Java heap, so this decides how many connections a process holds.
 */
class CoreClientMemoryTests {
    private val loops = LoopSet(LOOPS)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun anIdleClientConnectionHoldsUnderItsBudgetOfNativeMemory() =
        runBlocking {
            withCoreEchoServer { server ->
                val factory = FreeCountingFactory(BufferFactory.network(), setOf(FreeCountingFactory.Site.Core))
                val clients = ArrayList<CoreClient>()
                try {
                    repeat(LOOPS) { clients += connectedAndEchoed(server.port, factory) }
                    settle()
                    val before = factory.heldBytes()
                    val beforeByPlace = factory.heldByPlace()
                    repeat(CONNECTIONS) { clients += connectedAndEchoed(server.port, factory) }
                    settle()
                    val perConnection = (factory.heldBytes() - before) / CONNECTIONS
                    val inventory =
                        factory
                            .heldByPlace()
                            .map { (place, bytes) -> place to (bytes - beforeByPlace.getOrElse(place) { 0L }) / CONNECTIONS }
                            .filter { it.second > 0 }
                            .sortedByDescending { it.second }
                            .joinToString("\n") { "  ${it.second} B ${it.first}" }
                    println("[core client memory] $perConnection B per idle connection\n$inventory")
                    assertTrue(
                        perConnection < BUDGET,
                        "an idle client connection holds $perConnection B of native memory (budget $BUDGET B):\n$inventory",
                    )
                } finally {
                    clients.forEach { it.connection.close(QuicError.NoError) }
                }
            }
        }

    /** A connection that has carried one echo, so its stream and send paths have run. */
    private suspend fun connectedAndEchoed(
        port: Int,
        factory: BufferFactory,
    ): CoreClient {
        val client = connectCore(port, loops = loops, factory = factory)
        val stream = client.connection.openStream()
        val payload =
            BufferFactory.network().allocate(PAYLOAD).apply {
                repeat(PAYLOAD) { writeByte(it.toByte()) }
                resetForRead()
            }
        stream.writeFully(payload, 10.seconds)
        var received = 0
        while (received < PAYLOAD) {
            if (stream.read(10.seconds) {
                    received += it.remaining()
                    it.position(it.limit())
                } !is ScopedReadResult.Data<*>
            ) {
                break
            }
        }
        return client
    }

    /** Session tickets and acknowledgements arrive after the echo; the memory they take is the connection's. */
    private suspend fun settle() = delay(300.milliseconds)

    private companion object {
        const val LOOPS = 2
        const val CONNECTIONS = 16
        const val BUDGET = 64L * 1_024
        const val PAYLOAD = 64
    }
}
