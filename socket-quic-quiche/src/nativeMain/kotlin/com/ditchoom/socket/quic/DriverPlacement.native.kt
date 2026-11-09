package com.ditchoom.socket.quic

import kotlin.coroutines.CoroutineContext

/** Native drivers run on [productionDriverContext] (`Dispatchers.Default`) as given: there is no loop to hold. */
internal actual fun placeDriver(
    requested: CoroutineContext,
    unplaced: CoroutineContext,
): DriverPlacement = DriverPlacement(unplaced, null)

internal actual fun placedNear(
    driverContext: CoroutineContext,
    routedFrom: CoroutineContext,
): CoroutineContext = driverContext
