package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlin.random.Random

/** Whether a server checks address-validation tokens, and with what. */
internal sealed interface Tokens {
    data object None : Tokens

    class Of(
        val tokens: AddressTokens,
    ) : Tokens
}

/**
 * Version Negotiation and Retry: the packets a server answers with before it has a connection. quiche writes each
 * straight into the loop's send batch, which the pass sends with the connections' output; a batch with no room drops
 * the reply, and the client retransmits. Loop thread only.
 */
internal class Stateless(
    private val api: QuicheApi,
    val tokens: Tokens,
    private val random: Random,
    private val local: PathKey,
    private val counts: ServerCounts,
) {
    /** Answers the unsupported-version Initial at [datagram], read into [header], with a Version Negotiation. */
    fun negotiate(
        header: PacketHeader,
        datagram: NativeBytes,
        from: PathKey,
        out: SendBatch,
    ) {
        if (out.room() == Room.Full) return counts.replyDropped()
        // The reply's destination id is the packet's source id and its source id the packet's destination id.
        val written =
            api.negotiateVersion(
                datagram.address + header.scidOffset,
                header.scidLength,
                datagram.address + header.dcidOffset,
                header.dcidLength,
                out.storage.address + out.nextOffset(),
                out.limits.maxDatagramSize,
            )
        if (written <= 0) return counts.replyDropped()
        out.commit(written, SendTarget.ServerReply(to = from, from = ReplySource.Recorded(local)))
        counts.negotiationSent()
    }

    /** Answers the version 1 Initial at [datagram], which carries no token, with a Retry that carries one. */
    fun retry(
        tokens: AddressTokens,
        header: PacketHeader,
        datagram: NativeBytes,
        from: PathKey,
        scratch: DemuxScratch,
        now: Nanos,
        out: SendBatch,
    ) {
        if (out.room() == Room.Full) return counts.replyDropped()
        val c0 = random.nextLong()
        val c1 = random.nextLong()
        scratch.pickScid(c0, c1)
        val tokenLength = tokens.mint(scratch.tokenOut.owner, now.value, header.cid, c0, c1, from)
        val written =
            api.retry(
                datagram.address + header.scidOffset,
                header.scidLength,
                datagram.address + header.dcidOffset,
                header.dcidLength,
                scratch.newScid.address,
                QUIC_ISSUED_CONN_ID_LEN,
                scratch.tokenOut.address,
                tokenLength,
                QUIC_V1,
                out.storage.address + out.nextOffset(),
                out.limits.maxDatagramSize,
            )
        if (written <= 0) return counts.replyDropped()
        out.commit(written, SendTarget.ServerReply(to = from, from = ReplySource.Recorded(local)))
        counts.retrySent()
    }

    fun free() =
        when (tokens) {
            is Tokens.None -> Unit
            is Tokens.Of -> tokens.tokens.free()
        }

    private companion object {
        const val QUIC_V1 = 1
    }
}
