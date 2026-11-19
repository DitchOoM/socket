@file:OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.runBlocking
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What one 1 KiB echo allocates on the Kotlin heap on Kotlin/Native, client and server in this process together: the
 * heap grown over a run with the collector held off, read from the collections that bound it. A report, not a gate.
 */
class NativeEchoAllocationReport {
    @Test
    fun oneKibEchoesAllocate() =
        runBlocking {
            val loops = LoopSet(1)
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openStream()
                val out = BufferFactory.network().allocate(PAYLOAD)
                val echo: suspend () -> Unit = {
                    out.resetForWrite()
                    out.position(PAYLOAD)
                    out.resetForRead()
                    stream.writeFully(out, 10.seconds)
                    var got = 0
                    while (got < PAYLOAD) {
                        if (stream.read(10.seconds) { got += it.remaining() } !is ScopedRead.Data<*>) break
                    }
                }
                repeat(WARMUP) { echo() }
                val autotune = GC.autotune
                val target = GC.targetHeapBytes
                val interval = GC.regularGCInterval
                GC.autotune = false
                GC.regularGCInterval = Duration.INFINITE
                GC.targetHeapBytes = HELD_OFF
                try {
                    val perEcho = ArrayList<Long>()
                    var windows = 0
                    while (perEcho.size < WINDOWS && windows < WINDOWS * 10) {
                        windows++
                        GC.collect()
                        val start = GC.lastGCInfo!!
                        val base = start.memoryUsageAfter.values.sumOf { it.totalObjectsSizeBytes }
                        repeat(ECHOES) { echo() }
                        GC.collect()
                        val end = GC.lastGCInfo!!
                        // A window another collection interrupted is not read: its heap was already swept once.
                        if (end.epoch != start.epoch + 1) continue
                        perEcho.add((end.memoryUsageBefore.values.sumOf { it.totalObjectsSizeBytes } - base) / ECHOES)
                    }
                    perEcho.sort()
                    println(
                        "[core-kn-alloc] $PAYLOAD B echo, ${perEcho.size} clean windows of $ECHOES of $windows: " +
                            "median ${perEcho.getOrNull(
                                perEcho.size / 2,
                            )} B/echo, min ${perEcho.firstOrNull()}, max ${perEcho.lastOrNull()}",
                    )
                    assertTrue(perEcho.isNotEmpty(), "at least one window ran without another collection")
                } finally {
                    GC.targetHeapBytes = target
                    GC.regularGCInterval = interval
                    GC.autotune = autotune
                }
                client.connection.close(QuicError.NoError)
            }
            loops.close()
        }

    private companion object {
        const val PAYLOAD = 1_024
        const val WARMUP = 500
        const val ECHOES = 200
        const val WINDOWS = 15
        const val HELD_OFF = 2L shl 30
    }
}
