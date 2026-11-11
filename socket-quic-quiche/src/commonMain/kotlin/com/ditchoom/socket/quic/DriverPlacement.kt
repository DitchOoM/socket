package com.ditchoom.socket.quic

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

/**
 * Where one connection's driver (or one server's receive loop) runs, and the hold it keeps on that
 * place until [release]. Made by [placeDriver].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DriverPlacement(
    val context: CoroutineContext,
    private val onRelease: (() -> Unit)?,
) {
    private val released = AtomicBoolean(false)

    /** Ends the hold. Idempotent, and safe from any thread. */
    fun release() {
        if (onRelease != null && released.compareAndSet(expectedValue = false, newValue = true)) onRelease()
    }
}

/**
 * [driverContext], asking that a driver placed from it share the loop [routedFrom] runs on when that
 * loop is not much busier than the others (JVM/Android). Unchanged elsewhere, and when either context
 * names no loop.
 */
internal expect fun placedNear(
    driverContext: CoroutineContext,
    routedFrom: CoroutineContext,
): CoroutineContext

/**
 * Places a driver: when [requested] asks for the production placement ([productionDriverContext] on
 * JVM/Android), holds one event loop of the QUIC loop group and returns [requested] running on it.
 * Otherwise (a test's virtual-time or confined dispatcher, or a platform without loops) returns
 * [unplaced], which defaults to [requested], holding nothing.
 *
 * @throws DeadlineTimerUnavailableException on JVM/Android when no loop runs and no loop thread can be
 *   started: a driver whose timers and sockets nothing would ever service must be refused, not started.
 */
internal expect fun placeDriver(
    requested: CoroutineContext,
    unplaced: CoroutineContext = requested,
): DriverPlacement
