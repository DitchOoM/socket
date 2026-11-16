package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined

/** What the network monitor said, as a connection is told it. */
internal enum class NetworkChange {
    /** A usable network appeared where there was none. */
    Usable,

    /** The usable network changed to another one, and the socket was reopened on it at the same address and port. */
    Rehomed,

    /** No usable network at all. */
    Gone,

    /** A usable network without the local address the connection's socket is bound to, which it does not follow. */
    Moved,
}

/** What a connection does about a change. */
internal enum class NetworkStep {
    /** Have quiche send its probes at once: what the outage lost is resent now and answered in one round trip. */
    Nudge,

    /** Keep the connection: the idle timeout still bounds an outage, and a usable network nudges it. */
    Wait,

    /** The connection cannot go on from its socket: it ends now. */
    End,
}

/** Whether the network monitor last reported a usable network. */
private enum class Watch { Usable, Unusable }

/**
 * Whether the connection has a usable network. No usable network does not end it: a flap is ridden out, and a usable
 * network nudges it to send at once. Only an idle timeout that passes with no usable network ends it, and then as
 * [ConnEnd.NetworkLost], which says why the peer went silent. A socket moved to a new local address is on a usable
 * network ([moved]).
 */
@Confined
internal class NetworkWatch {
    private var watch = Watch.Usable

    fun changed(change: NetworkChange): NetworkStep =
        when (change) {
            NetworkChange.Usable, NetworkChange.Rehomed -> {
                watch = Watch.Usable
                NetworkStep.Nudge
            }
            NetworkChange.Gone -> {
                watch = Watch.Unusable
                NetworkStep.Wait
            }
            NetworkChange.Moved -> NetworkStep.End
        }

    /** The socket moved to a new local address: there is a usable network. */
    fun moved() {
        watch = Watch.Usable
    }

    /** The end quiche reported, named [ConnEnd.NetworkLost] when it is an idle timeout with no usable network. */
    fun endOf(quiche: ConnEnd): ConnEnd =
        when (watch) {
            Watch.Usable -> quiche
            Watch.Unusable -> if (quiche is ConnEnd.IdleTimeout) ConnEnd.NetworkLost else quiche
        }
}
