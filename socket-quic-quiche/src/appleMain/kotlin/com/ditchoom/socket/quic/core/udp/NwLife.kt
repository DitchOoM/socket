@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NetworkErrorDomain
import com.ditchoom.socket.quic.NetworkRefusal
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Where a Network.framework connection is, as its state handler last said. */
internal sealed interface NwState {
    data object Starting : NwState

    data object Ready : NwState

    /** Failed or cancelled, before or after it was ready. */
    class Ended(
        val refusal: NetworkRefusal,
    ) : NwState
}

/** What a connect waiting on the state handler learns from one state change. */
internal sealed interface NwVerdict {
    data object Ready : NwVerdict

    class Refused(
        val refusal: NetworkRefusal,
    ) : NwVerdict
}

/** Whether a state change settled the connect: the first ready or refusal does, once. */
internal sealed interface NwSettled {
    class First(
        val verdict: NwVerdict,
    ) : NwSettled

    data object Not : NwSettled
}

/**
 * A Network.framework connection's state, written by its state handler and read by any thread. The handler runs on a
 * concurrent queue, so two changes can arrive at once: the connect is settled by compare-and-set, exactly once.
 */
@Published
internal class NwLife {
    private val state = AtomicReference<NwState>(NwState.Starting)

    fun view(): NwState = state.load()

    /** Records one state change (`nw_connection_state_t`, NW's error domain and code, the path's status). */
    fun changed(
        nwState: Int,
        domain: Int,
        code: Int,
        pathStatus: Int,
    ): NwSettled {
        val next =
            when (nwState) {
                NW_READY -> NwState.Ready
                NW_FAILED, NW_CANCELLED -> NwState.Ended(NetworkRefusal(domainOf(domain), code))
                // Waiting on a path that cannot be satisfied (no route of the peer's family): it would wait for ever.
                NW_WAITING ->
                    if (pathStatus == PATH_UNSATISFIED) NwState.Ended(NetworkRefusal(domainOf(domain), code)) else return NwSettled.Not
                else -> return NwSettled.Not
            }
        while (true) {
            val seen = state.load()
            when (seen) {
                is NwState.Ended -> return NwSettled.Not
                is NwState.Ready ->
                    when (next) {
                        is NwState.Ended -> if (state.compareAndSet(seen, next)) return NwSettled.Not
                        is NwState.Ready, is NwState.Starting -> return NwSettled.Not
                    }
                is NwState.Starting ->
                    if (state.compareAndSet(seen, next)) {
                        return when (next) {
                            is NwState.Ended -> NwSettled.First(NwVerdict.Refused(next.refusal))
                            is NwState.Ready, is NwState.Starting -> NwSettled.First(NwVerdict.Ready)
                        }
                    }
            }
        }
    }

    /** Our own cancel: the connection is ended for every later send and receive. */
    fun cancelled() {
        state.store(NwState.Ended(NetworkRefusal(NetworkErrorDomain.Unreported, 0)))
    }

    private fun domainOf(domain: Int): NetworkErrorDomain =
        when (domain) {
            DOMAIN_POSIX -> NetworkErrorDomain.Posix
            DOMAIN_DNS -> NetworkErrorDomain.Dns
            DOMAIN_TLS -> NetworkErrorDomain.Tls
            DOMAIN_NONE -> NetworkErrorDomain.Unreported
            else -> NetworkErrorDomain.Other
        }

    private companion object {
        const val NW_WAITING = 1
        const val NW_READY = 3
        const val NW_FAILED = 4
        const val NW_CANCELLED = 5
        const val PATH_UNSATISFIED = 2
        const val DOMAIN_NONE = 0
        const val DOMAIN_POSIX = 1
        const val DOMAIN_DNS = 2
        const val DOMAIN_TLS = 3
    }
}
