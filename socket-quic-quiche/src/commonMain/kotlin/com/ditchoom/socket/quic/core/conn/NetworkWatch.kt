package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined

/** What the network monitor said, as a connection is told it. */
internal enum class NetworkChange {
    /** A usable network appeared where there was none. */
    Usable,

    /** The usable network changed to another one. */
    Changed,

    /** No usable network at all. */
    Gone,

    /** A usable network, without the local address the connection's socket is bound to. */
    Moved,
}

/** What a connection does about a change. */
internal enum class NetworkStep {
    /** Have quiche send at once: what its backed-off timers hold back is recovered in about one round trip. */
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
 * [ConnEnd.NetworkLost], which says why the peer went silent. Migration will act on [NetworkChange.Moved].
 */
@Confined
internal class NetworkWatch {
    private var watch = Watch.Usable

    fun changed(change: NetworkChange): NetworkStep =
        when (change) {
            NetworkChange.Usable, NetworkChange.Changed -> {
                watch = Watch.Usable
                NetworkStep.Nudge
            }
            NetworkChange.Gone -> {
                watch = Watch.Unusable
                NetworkStep.Wait
            }
            NetworkChange.Moved -> NetworkStep.End
        }

    /** The end quiche reported, named [ConnEnd.NetworkLost] when it is an idle timeout with no usable network. */
    fun endOf(quiche: ConnEnd): ConnEnd =
        when (watch) {
            Watch.Usable -> quiche
            Watch.Unusable -> if (quiche is ConnEnd.IdleTimeout) ConnEnd.NetworkLost else quiche
        }
}
