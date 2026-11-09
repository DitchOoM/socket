package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * SPIKE, measurement only, never merged (branch perf/owned-loop-spike).
 *
 * `-Dquic.spike.loop=`
 *  - `shared` (default): today's dispatch: drivers on Dispatchers.Default, server loop/readers/handlers on
 *    Dispatchers.IO, both with the DeadlineTimer.
 *  - `one`: ONE owned single-thread event loop (a ScheduledExecutorService, so its own timers) runs every
 *    driver loop, the server's receive loop and reader, every accepted connection's handler, and the
 *    client/server parent scopes.
 *  - `group`: N owned loops (`-Dquic.spike.loopGroupSize`, default availableProcessors). Each connection
 *    (client side and server side independently) is pinned round-robin to one loop: its driver, its parent
 *    scope and (server) its handler. The server's receive loop and reader run on loop 0.
 *  - `oneSel` / `groupSel`: as `one` / `group`, but each loop is a SpikeSelectorLoop: one thread owning ONE
 *    Selector that multiplexes all its connections' UDP sockets, plus its timers and tasks. No per-channel
 *    select() thread, no hand-off from a selector thread to the driver.
 *  - `limited1`: Dispatchers.Default.limitedParallelism(1) as the single loop: still the shared scheduler
 *    and its global queue, a separate variant, not an owned loop.
 *
 * Read once, at class init: set it before the first QUIC class loads.
 */
internal object OwnedLoopSpike {
    val mode: String = System.getProperty("quic.spike.loop") ?: if (System.getProperty("quic.spike.ownedLoop") == "true") "one" else "shared"
    val enabled: Boolean = mode != "shared"

    private val loops: List<CoroutineDispatcher> by lazy {
        when (mode) {
            "limited1" -> listOf(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1))
            "one" -> listOf(newLoop(0))
            "group" -> List(groupSize()) { newLoop(it) }
            // Each loop owns one Selector that multiplexes its connections' sockets (Netty-style).
            "oneSel" -> listOf(com.ditchoom.socket.udp.SpikeSelectorLoop("quic-selector-loop-0"))
            "groupSel" -> List(groupSize()) { com.ditchoom.socket.udp.SpikeSelectorLoop("quic-selector-loop-$it") }
            else -> error("spike loop mode $mode has no loops")
        }
    }

    private fun groupSize(): Int = System.getProperty("quic.spike.loopGroupSize")?.toInt() ?: Runtime.getRuntime().availableProcessors()

    private fun newLoop(i: Int): CoroutineDispatcher =
        Executors
            .newSingleThreadScheduledExecutor { r -> Thread(r, "quic-owned-loop-$i").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    private val next = AtomicInteger()

    /** Loop 0: the server's receive loop and reader. */
    val dispatcher: CoroutineDispatcher get() = loops[0]

    /** The loop the next connection is pinned to. */
    fun nextLoop(): CoroutineDispatcher = loops[Math.floorMod(next.getAndIncrement(), loops.size)]

    init {
        System.err.println("# quic spike: loop mode $mode")
    }
}

internal actual fun spikeAcceptedDriverContext(default: CoroutineContext): CoroutineContext =
    if (OwnedLoopSpike.enabled) OwnedLoopSpike.nextLoop() else default

internal actual fun spikeHandlerContext(
    driverLoop: CoroutineContext,
    default: CoroutineContext,
): CoroutineContext = if (OwnedLoopSpike.enabled) driverLoop else default
