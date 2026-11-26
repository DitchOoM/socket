package com.ditchoom.socket.quic

/** Whether a Retire Prior To of ours (RFC 9000 §19.15) asked the peer to retire an id of ours it retired. */
internal enum class PeerRetirement {
    /** The id was below the Retire Prior To quiche had sent. */
    Asked,

    /** The id was at or above it: the peer retired it on its own (RFC 9000 §19.16). */
    Unasked,
}

/**
 * Our source connection ids quiche holds, by sequence number, and the Retire Prior To it has sent, kept the
 * way quiche's `cid.rs` keeps them: what tells a retirement the peer was asked for from one it made unasked.
 * The handshake id is sequence 0 and the only id not handed to [issued]; quiche numbers the rest 1, 2, … in
 * the order it takes them. Loop only.
 */
internal class OurSourceIds {
    private var seqs = LongArray(INITIAL_CAPACITY)
    private var his = LongArray(INITIAL_CAPACITY)
    private var los = LongArray(INITIAL_CAPACITY)

    /** Live ids, in ascending sequence order; starts with the handshake id. */
    private var count = 1
    private var nextSeq = 1L
    private var retirePriorTo = 0L

    /**
     * quiche took the id whose big-endian halves are [hi] and [lo]. Taken while quiche already held
     * [peerLimit] ids (the peer's `active_connection_id_limit`, which quiche treats as at least 2), it raised
     * Retire Prior To past the lowest id not yet asked for (`new_scid` with retire_if_needed).
     */
    fun issued(
        hi: Long,
        lo: Long,
        peerLimit: Long,
    ) {
        if (count >= maxOf(peerLimit, QUICHE_MIN_PEER_LIMIT)) retirePriorTo = lowestUsable() + 1
        if (count == seqs.size) grow()
        seqs[count] = nextSeq++
        his[count] = hi
        los[count] = lo
        count++
    }

    /** The peer retired the id whose big-endian halves are [hi] and [lo]; the handshake id when it was never issued here. */
    fun peerRetired(
        hi: Long,
        lo: Long,
    ): PeerRetirement {
        for (i in 0 until count) {
            if (seqs[i] != HANDSHAKE_SEQ && his[i] == hi && los[i] == lo) return retireAt(i)
        }
        return peerRetiredHandshakeId()
    }

    /**
     * The peer retired the handshake id. quiche yields each retired id once, so a record without it means an id
     * this never saw; that changes nothing and is reported [PeerRetirement.Asked].
     */
    fun peerRetiredHandshakeId(): PeerRetirement = if (count > 0 && seqs[0] == HANDSHAKE_SEQ) retireAt(0) else PeerRetirement.Asked

    private fun retireAt(index: Int): PeerRetirement {
        val retirement = if (seqs[index] < retirePriorTo) PeerRetirement.Asked else PeerRetirement.Unasked
        seqs.copyInto(seqs, index, index + 1, count)
        his.copyInto(his, index, index + 1, count)
        los.copyInto(los, index, index + 1, count)
        count--
        // quiche's `retire_scid`: Retire Prior To moves up to the lowest id still usable.
        retirePriorTo = lowestUsable()
        return retirement
    }

    /** The lowest sequence at or above Retire Prior To; Retire Prior To itself when none is (quiche refuses then). */
    private fun lowestUsable(): Long {
        for (i in 0 until count) if (seqs[i] >= retirePriorTo) return seqs[i]
        return retirePriorTo
    }

    private fun grow() {
        seqs = seqs.copyOf(seqs.size * 2)
        his = his.copyOf(his.size * 2)
        los = los.copyOf(los.size * 2)
    }

    companion object {
        private const val INITIAL_CAPACITY = 8
        private const val HANDSHAKE_SEQ = 0L

        /** The fewest ids quiche lets a peer hold: its limit before the peer's parameters arrive, and the floor it puts under one. */
        const val QUICHE_MIN_PEER_LIMIT = 2L
    }
}
