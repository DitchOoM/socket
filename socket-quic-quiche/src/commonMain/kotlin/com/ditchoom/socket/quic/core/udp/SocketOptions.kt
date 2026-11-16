package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SocketRefusal

/** Where a native socket sends: one peer it is connected to, or any address a datagram names. */
internal sealed interface SocketPeer {
    class Connected(
        val peer: PathKey,
    ) : SocketPeer

    data object Unconnected : SocketPeer
}

/** What [NativeSocket.open] did. */
internal sealed interface NativeOpened {
    class Opened(
        val socket: NativeSocket,
    ) : NativeOpened

    class Refused(
        val refusal: SocketRefusal,
    ) : NativeOpened
}

/** Whether a socket asks for `UDP_GRO`. */
internal sealed interface GroRequest {
    /** The socket does not ask for `UDP_GRO`. */
    data object Declined : GroRequest

    /** The socket asks for it; `qnet` keeps the receive scratch of one that got it. */
    data object Requested : GroRequest
}

/** What a socket's `UDP_GRO` came to, decided at open and again at every reopen. */
internal sealed interface GroSetup {
    /** Not asked for: the socket's batching ceiling does not use it. */
    data object NotRequested : GroSetup

    /** Asked for, and this platform offers none. */
    data object Unavailable : GroSetup

    /** Asked for, and the kernel refused it: the socket receives one datagram per message. */
    class Refused(
        val refusal: SocketRefusal,
    ) : GroSetup

    data object Enabled : GroSetup
}

/**
 * What opening the socket did about IP fragmentation (RFC 9000 §14). A datagram over the link's MTU is refused with
 * EMSGSIZE only while [Set]; on [Refused] the kernel may fragment it, and path MTU discovery cannot see the limit.
 */
internal sealed interface DontFragment {
    /** DF is set on every datagram of the socket. */
    data object Set : DontFragment

    /** The platform refused the option, with [refusal]. */
    class Refused(
        val refusal: SocketRefusal,
    ) : DontFragment

    /** This platform's sockets have no such option. */
    data object NotOffered : DontFragment
}

/** The options [applyOptions] set on one open descriptor. */
internal class AppliedOptions(
    val gro: GroSetup,
    val dontFragment: DontFragment,
)

/**
 * Sets the options of a freshly opened descriptor: DF always, `UDP_GRO` when [wantsGro]. Each is its own `qnet` call, so
 * a new option is a new call and a new result type here, never a new offset in the spec block.
 */
internal fun applyOptions(
    shim: NetShim,
    fd: Int,
    family: Int,
    wantsGro: Boolean,
): AppliedOptions {
    val df = shim.udpSetDontFragment(fd, family)
    val gro =
        if (!wantsGro) {
            GroSetup.NotRequested
        } else {
            val result = shim.udpSetGro(fd)
            when {
                result > 0 -> GroSetup.Enabled
                result == 0 -> GroSetup.Unavailable
                else -> GroSetup.Refused(SocketRefusal.of(-result))
            }
        }
    return AppliedOptions(gro, if (df == 0) DontFragment.Set else DontFragment.Refused(SocketRefusal.of(-df)))
}
