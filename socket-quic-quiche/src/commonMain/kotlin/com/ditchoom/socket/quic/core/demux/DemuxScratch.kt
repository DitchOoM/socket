package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** What an Initial's header said about its address-validation token. */
internal enum class TokenRead {
    /** quiche could not parse the header. */
    Refused,

    /** The Initial carries no token. */
    Absent,

    /** The token is in [DemuxScratch.token], [DemuxScratch.tokenLength] bytes long. */
    Present,
}

/**
 * The native out-parameters of `quiche_header_info`, read for the token of an Initial, and the memory new connection
 * ids and Retry tokens are written into. A sub-part of [Demux]; loop thread only.
 */
@LoopOnly
internal class DemuxScratch(
    /** [FIELDS_BYTES] bytes for the header's other fields and the native lengths. */
    private val fields: NativeBytes,
    /** The token an Initial carried, at most [TOKEN_CAPACITY] bytes. */
    val token: NativeBytes,
    /** A token this server mints for a Retry, at most [TOKEN_CAPACITY] bytes. */
    val tokenOut: NativeBytes,
    /** A connection id this server picks, [QUIC_ISSUED_CONN_ID_LEN] bytes. */
    val newScid: NativeBytes,
) {
    var tokenLength = 0
        private set

    /** Reads the token of the Initial at [datagram]; its bytes are left in [token]. */
    fun readToken(
        api: QuicheApi,
        datagram: NativeBytes,
        length: Int,
    ): TokenRead {
        fields.putLong(SCID_LEN, CidRead.MAX_CID_LENGTH.toLong())
        fields.putLong(DCID_LEN, CidRead.MAX_CID_LENGTH.toLong())
        fields.putLong(TOKEN_LEN, TOKEN_CAPACITY.toLong())
        val rc =
            api.headerInfo(
                datagram.address,
                length,
                QUIC_ISSUED_CONN_ID_LEN,
                fields.address + VERSION,
                fields.address + TYPE,
                fields.address + SCID,
                fields.address + SCID_LEN,
                fields.address + DCID,
                fields.address + DCID_LEN,
                token.address,
                fields.address + TOKEN_LEN,
            )
        if (rc < 0) return TokenRead.Refused
        tokenLength = fields.longAt(TOKEN_LEN).toInt()
        return if (tokenLength == 0) TokenRead.Absent else TokenRead.Present
    }

    /** Writes a fresh random id into [newScid]; [c0] and [c1] are its two halves as [CidRead] holds them. */
    fun pickScid(
        c0: Long,
        c1: Long,
    ) {
        val out = newScid.owner
        out.resetForWrite()
        out.writeLong(c0)
        out.writeLong(c1)
    }

    fun free() {
        fields.free()
        token.free()
        tokenOut.free()
        newScid.free()
    }

    companion object {
        const val TOKEN_CAPACITY = 256
        private const val WORD = 8
        private const val VERSION = 0
        private const val TYPE = WORD
        private const val SCID_LEN = 2 * WORD
        private const val DCID_LEN = 3 * WORD
        private const val TOKEN_LEN = 4 * WORD
        private const val SCID = 5 * WORD
        private const val DCID = SCID + CidRead.MAX_CID_LENGTH
        const val FIELDS_BYTES = DCID + CidRead.MAX_CID_LENGTH
    }
}
