package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.CoroutineContext

/**
 * Native drivers run on a lane of their own: for the production placement ([productionDriverContext],
 * `Dispatchers.Default` with its timers on [NativeDeadlineTimer.Shared]) each call returns a new `limitedParallelism(1)` view of it, so one
 * connection's loop and every side coroutine launched with its loop context (its readers, send retries,
 * path opener, trace collectors) run one at a time, with a happens-before edge between consecutive tasks.
 * That is what makes "only the loop touches the connection" a property of where the coroutines run and not
 * only of how the code is written. It is a view of the shared pool, not a thread: nothing is held, so there
 * is nothing to release, and a lane that is idle costs nothing.
 *
 * The lane is a view of [productionDispatcher], not of `Dispatchers.Default`: kotlinx's `LimitedDispatcher`
 * is `Delay by (parent as? Delay ?: DefaultDelay)` (kotlinx-coroutines-core 1.11.0, `internal/LimitedDispatcher.kt`),
 * so a view of a dispatcher that is a `Delay` keeps its timers and one of a plain dispatcher goes back to
 * `DefaultExecutor`. `ProductionDriverContextTests` fails if that ever happens (checked by making the lane a view
 * of `Dispatchers.Default` again: the lane test fails).
 *
 * Anything else a caller requests (a test's dispatcher) is used as given, as before. Application code never
 * runs here: handlers, trace delivery to user sinks and the migration reactor keep their own dispatchers.
 */
internal actual fun placeDriver(
    requested: CoroutineContext,
    unplaced: CoroutineContext,
): DriverPlacement =
    if (requested === productionDriverContext) {
        DriverPlacement(connectionLane(), null)
    } else {
        DriverPlacement(unplaced, null)
    }

@OptIn(ExperimentalCoroutinesApi::class)
private fun connectionLane(): CoroutineDispatcher = productionDispatcher.limitedParallelism(1, "quic-lane")

internal actual fun placedNear(
    driverContext: CoroutineContext,
    routedFrom: CoroutineContext,
): CoroutineContext = driverContext
