@file:OptIn(InternalDatagramLoopApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.DatagramEventLoop
import com.ditchoom.socket.udp.DatagramEventLoopGroup
import com.ditchoom.socket.udp.DatagramEventLoopUnavailableException
import com.ditchoom.socket.udp.InternalDatagramLoopApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * The event loops every JVM/Android QUIC connection runs on: one per core, each a platform thread that
 * owns one `Selector` for all the UDP sockets of the connections it serves, plus their timers and their
 * driver coroutines ([com.ditchoom.socket.udp.DatagramEventLoop]).
 *
 * ## Why loops, and not the shared coroutine pools
 *
 * Before this, every connection's socket waited in a `select()` of its own, parked on a
 * `Dispatchers.IO` thread, and the drivers ran on a shared pool:
 *
 * - **A process stalled at about 64 connections.** `Dispatchers.IO` has 64 threads; 64 parked receives
 *   (plus a server's) took all of them, and every round trip then timed out. A loop parks one thread
 *   for all of its sockets.
 * - **Every datagram crossed threads several times.** A 64-byte echo cost about 22 shared-pool
 *   dispatches and 37 thread unparks; on a loop the socket, the timers and the driver are one thread,
 *   and only the hand-off to and from the application remains.
 * - **The shared scheduler could lose a driver's wakeup** (kotlinx.coroutines #4491; see
 *   `LostCpuWakeupTests`). A loop is not a `CoroutineScheduler` and does not depend on it.
 *
 * ## What runs where
 *
 * - On a loop: a connection's driver, its UDP reader loops and its timers; a server's receive loop.
 *   Library code only, and none of it blocks.
 * - Never on a loop: anything the application supplied. Server handlers run on [serverReceiveDispatcher]
 *   (`Dispatchers.IO`), a client's block on the caller's dispatcher, and a connection's own scope
 *   ([applicationScopeDispatcher]) on `Dispatchers.IO`. A loop thread blocked by application code would
 *   stall every connection on it.
 *
 * A connection is placed on the least held loop when its driver starts ([placeDriver]) and stays there.
 * A loop's thread exits a second after its last connection ends, and is started again by the next.
 */
internal val quicLoopGroup: DatagramEventLoopGroup =
    DatagramEventLoopGroup(size = loopGroupSize(), namePrefix = "quic-loop")

/**
 * One loop per core, unless `-Dcom.ditchoom.socket.quic.loops=<n>` says otherwise (read once, when the
 * first QUIC connection or server starts).
 */
private fun loopGroupSize(): Int =
    System.getProperty(LOOPS_PROPERTY)?.toIntOrNull()?.takeIf { it > 0 }
        ?: maxOf(1, Runtime.getRuntime().availableProcessors())

private const val LOOPS_PROPERTY = "com.ditchoom.socket.quic.loops"

/**
 * The context element that asks for a placement on [group] rather than naming a dispatcher: each
 * connection that starts with it is held on one loop of the group, chosen when it starts.
 */
internal class LoopPlacement(
    val group: DatagramEventLoopGroup,
    /** A loop to share when it is not much busier than the rest; see [DatagramEventLoopGroup.hold]. */
    val near: DatagramEventLoop? = null,
) : AbstractCoroutineContextElement(LoopPlacement) {
    companion object Key : CoroutineContext.Key<LoopPlacement>

    override fun toString(): String = "LoopPlacement(${group.loops.size} loops${near?.let { ", near $it" } ?: ""})"
}

/**
 * [driverContext], asking for a placement near the loop [routedFrom] runs on: a server's accepted
 * connections are placed near the receive loop that routes every inbound datagram to them.
 */
internal actual fun placedNear(
    driverContext: CoroutineContext,
    routedFrom: CoroutineContext,
): CoroutineContext {
    val placement = driverContext[LoopPlacement] ?: return driverContext
    val loop = routedFrom[ContinuationInterceptor] as? DatagramEventLoop ?: return driverContext
    return driverContext + LoopPlacement(placement.group, near = loop)
}

/** What every JVM/Android connection and server is placed by: the shared [quicLoopGroup]. */
internal actual val productionDriverContext: CoroutineContext = LoopPlacement(quicLoopGroup)

internal actual fun placeDriver(
    requested: CoroutineContext,
    unplaced: CoroutineContext,
): DriverPlacement {
    val placement = requested[LoopPlacement] ?: return DriverPlacement(unplaced, null)
    val hold =
        try {
            placement.group.hold(placement.near)
        } catch (unavailable: DatagramEventLoopUnavailableException) {
            throw DeadlineTimerUnavailableException(unavailable.cause ?: unavailable)
        }
    // Every other element the caller added (a test's thread marker, a name) is kept.
    return DriverPlacement(requested.minusKey(LoopPlacement) + hold.loop) { hold.release() }
}
