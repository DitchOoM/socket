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
     * [from] is a [ReplySource] rather than a bare [PathKey] because "quiche named no source" is a real
     * state that must not be spelled as a sentinel — see that type.
     */
    data class ServerReply(
        val to: PathKey,
        val from: ReplySource,
    ) : SendTarget
}

/**
 * What quiche said about the local address a server reply must leave from.
 *
 * Exists because the alternative is a sentinel: `decodePathKey` answers `PathKey(family = 0, …)` for a
 * backend that decodes no egress address (a test double, or any channel whose [QuicheApi] leaves the
 * sockaddr accessors unbound), and branching on `family == 0` would spread that magic number across
 * every server egress channel. It is converted **once**, at the driver's boundary with quiche, and the
 * rest of the code gets an exhaustive `when`.
 *
 * Keeping the two cases apart also keeps them *diagnosable*, which is why they are not collapsed into
 * one "no source" case even though both end up sending with the platform's own choice: [Undecodable] is
 * expected and permanent for that backend, whereas a [Recorded] key the receive loop cannot resolve is
 * a bug in this server's own bookkeeping.
 */
sealed interface ReplySource {
    /** quiche named a local address for this path; [key] is its decoded [PathKey]. */
    data class Recorded(
        val key: PathKey,
    ) : ReplySource

    /** This backend decodes no egress address at all, so there is nothing to pin. */
    data object Undecodable : ReplySource
}
