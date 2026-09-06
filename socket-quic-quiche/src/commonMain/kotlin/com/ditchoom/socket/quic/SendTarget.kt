package com.ditchoom.socket.quic

/**
 * Where one outgoing datagram goes — and, when the platform has a choice, which local address it must
 * leave from.
 *
 * Replaces the former `dest: PathKey?` parameter of [UdpChannel.send], whose `null` carried meaning
 * ("send to the channel's own connected peer"). That nullable also made #556 *representable*: a server
 * could name the destination of a reply and say nothing about its source, which is precisely a reply
 * that leaves from whichever local address the kernel prefers. Here the two travel together or not at
 * all, so "routed the reply, forgot the source" no longer type-checks.
 */
sealed interface SendTarget {
    /**
     * Send to the channel's own connected/fixed peer, from its own bound address.
     *
     * The client case, and every channel bound to exactly one 4-tuple: there is no choice for the
     * platform to make and nothing for the caller to name.
     */
    data object ConnectedPeer : SendTarget

    /**
     * A server reply over a shared — possibly wildcard-bound — socket.
     *
     * Send [to] the destination quiche chose (`send_info.to`), so a migrated client's new source
     * address receives its replies (RFC 9000 §9); and leave [from] the local address quiche recorded
     * for that path (`send_info.from`), which is the `recv_info.to` the receive loop set from the
     * arriving datagram's own local address.
     *
     * **Naming [from] is the whole of the #556 fix.** A wildcard-bound server that lets the kernel
     * choose answers from whichever address routing prefers, and a client that `connect()`ed to a
     * different local address of the same host drops every one of those replies as off-path. Darwin
     * and Linux disagree about which address the kernel picks — Darwin matches the destination, Linux
     * the primary — which is why the identical server passes on one and is deaf on the other.
     *
     * [from] may carry `family == 0`: a backend that decodes no egress address at all (the test
     * doubles, and any channel whose [QuicheApi] leaves the sockaddr accessors unbound). That is a
     * *known-absent* source rather than a wrong one — the channel then lets the platform choose, which
     * is the pre-#556 behaviour and the only answer available to it.
     */
    data class ServerReply(
        val to: PathKey,
        val from: PathKey,
    ) : SendTarget
}
