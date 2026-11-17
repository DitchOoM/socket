package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlin.random.Random

/** Whether a server checks and issues address-validation tokens, and with what. */
internal sealed interface Tokens {
    data object None : Tokens

    class Of(
        val tokens: AddressTokens,
        val validation: AddressValidation,
        val future: FutureTokens,
    ) : Tokens

    companion object {
        fun of(
            config: ServerConfig,
            factory: BufferFactory,
            random: Random,
        ): Tokens {
            val validation = config.addressValidation
            val future = config.futureTokens
            val retryLifetime =
                when (validation) {
                    is AddressValidation.Off ->
                        when (future) {
                            is FutureTokens.NotIssued -> return None
                            is FutureTokens.Issued -> AddressValidation.Retry().lifetime
                        }
                    is AddressValidation.Retry -> validation.lifetime
                }
            val futureLifetime =
                when (future) {
                    is FutureTokens.NotIssued -> retryLifetime
                    is FutureTokens.Issued -> future.lifetime
                }
            val tokens = AddressTokens(factory, random, retryLifetime.inWholeNanoseconds, futureLifetime.inWholeNanoseconds)
            return Of(tokens, validation, future)
        }
    }
}

/** What an Initial's token says: start a connection [how], or the Initial was answered or dropped. */
internal sealed interface Screened {
    class Start(
        val how: Admit,
    ) : Screened

    data object Answered : Screened
}

/**
 * Version Negotiation and Retry: the packets a server answers with before it has a connection, and the NEW_TOKEN
 * tokens it hands a connection it accepted. quiche writes each reply straight into the loop's send batch, which the
 * pass sends with the connections' output; a batch with no room drops the reply, and the client retransmits. Loop
 * thread only.
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

    /**
     * Checks the token of the version 1 Initial at [datagram], read into [header]. A failed Retry token is dropped; an
     * absent or unproven NEW_TOKEN token is no token: a Retry when the server validates, else a fresh start.
     */
    fun screen(
        tokens: Tokens.Of,
        header: PacketHeader,
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        scratch: DemuxScratch,
        now: Nanos,
        out: SendBatch,
    ): Screened {
        when (scratch.readToken(api, datagram, length)) {
            TokenRead.Refused -> counts.unknownDropped()
            TokenRead.Absent -> return unproven(tokens, header, datagram, from, scratch, now, out)
            TokenRead.Present -> {
                val checked = tokens.tokens.check(scratch.token.owner, scratch.tokenLength, now.value, header.cid, from)
                when (checked) {
                    is TokenCheck.Valid -> return Screened.Start(Admit.Redeemed(checked))
                    is TokenCheck.Proven -> return Screened.Start(Admit.Proven)
                    is TokenCheck.Unproven -> return unproven(tokens, header, datagram, from, scratch, now, out)
                    is TokenCheck.Malformed, is TokenCheck.Forged, is TokenCheck.Expired -> counts.tokenDropped()
                }
            }
        }
        return Screened.Answered
    }

    /** An Initial with no token that proves its address (RFC 9000 §8.1.3): a Retry if the server validates, else in. */
    private fun unproven(
        tokens: Tokens.Of,
        header: PacketHeader,
        datagram: NativeBytes,
        from: PathKey,
        scratch: DemuxScratch,
        now: Nanos,
        out: SendBatch,
    ): Screened =
        when (tokens.validation) {
            is AddressValidation.Off -> Screened.Start(Admit.Fresh)
            is AddressValidation.Retry -> Screened.Answered.also { retry(tokens.tokens, header, datagram, from, scratch, now, out) }
        }

    /** Answers the version 1 Initial at [datagram], which carries no token, with a Retry that carries one. */
    private fun retry(
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

    /**
     * [handle] was just accepted from [from] as [how], before its first datagram: a client that proved its address
     * with a NEW_TOKEN token has no anti-amplification limit, and a NEW_TOKEN token for its next connection is queued.
     */
    fun accepted(
        handle: QuicheConn,
        how: Admit,
        from: PathKey,
        scratch: DemuxScratch,
        now: Nanos,
    ) {
        if (tokens !is Tokens.Of) return
        when (how) {
            is Admit.Proven -> if (api.connSetPeerAddressValidated(handle) == 0) counts.tokenProven()
            is Admit.Fresh, is Admit.Redeemed -> Unit
        }
        when (tokens.future) {
            is FutureTokens.NotIssued -> Unit
            is FutureTokens.Issued -> {
                val length = tokens.tokens.mintFuture(scratch.tokenOut.owner, now.value, from)
                if (api.connSendNewToken(handle, scratch.tokenOut.address, length) == 0) counts.newTokenQueued()
            }
        }
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
