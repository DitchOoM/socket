@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.udp.SocketFault
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal sealed interface SocketHealth {
    data object Live : SocketHealth

    class Dead(
        val fault: SocketFault,
    ) : SocketHealth
}

/** Whether this call was the one that ended the socket. */
internal enum class Death { First, Already }

/** A socket's health, written once (Live to Dead) by whoever sees it die first, readable from any thread. */
@Published
internal class HealthCell {
    private val state = AtomicReference<SocketHealth>(SocketHealth.Live)

    fun view(): SocketHealth = state.load()

    fun died(fault: SocketFault): Death =
        if (state.compareAndSet(SocketHealth.Live, SocketHealth.Dead(fault))) Death.First else Death.Already
}
