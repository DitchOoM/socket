package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.core.edge.addressOf
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.readNativeSizeT
import com.ditchoom.socket.quic.writeNativeSizeT

/** The native out-parameters of `quiche_header_info`, read for the token of an Initial. */
internal class HeaderScratch(
    factory: BufferFactory,
) {
    private val version = factory.allocate(8)
    private val type = factory.allocate(8)
    private val scid = factory.allocate(CidRead.MAX_CID_LENGTH)
    private val scidLen = factory.allocate(8)
    private val dcid = factory.allocate(CidRead.MAX_CID_LENGTH)
    private val dcidLen = factory.allocate(8)
    val token: PlatformBuffer = factory.allocate(TOKEN_CAPACITY)
    private val tokenLen = factory.allocate(8)

    /** The length of the token in the Initial at [buffer], left in [token]; 0 when it has none; -1 when quiche refuses the header. */
    fun readToken(
        api: QuicheApi,
        buffer: ReceiveBuffer,
        length: Int,
    ): Int {
        writeNativeSizeT(scidLen, CidRead.MAX_CID_LENGTH)
        writeNativeSizeT(dcidLen, CidRead.MAX_CID_LENGTH)
        writeNativeSizeT(tokenLen, TOKEN_CAPACITY)
        val rc =
            api.headerInfo(
                buffer.address,
                length,
                QUIC_ISSUED_CONN_ID_LEN,
                addressOf(version),
                addressOf(type),
                addressOf(scid),
                addressOf(scidLen),
                addressOf(dcid),
                addressOf(dcidLen),
                addressOf(token),
                addressOf(tokenLen),
            )
        return if (rc < 0) -1 else readNativeSizeT(tokenLen)
    }

    fun free() {
        version.freeNativeMemory()
        type.freeNativeMemory()
        scid.freeNativeMemory()
        scidLen.freeNativeMemory()
        dcid.freeNativeMemory()
        dcidLen.freeNativeMemory()
        token.freeNativeMemory()
        tokenLen.freeNativeMemory()
    }

    private companion object {
        const val TOKEN_CAPACITY = 256
    }
}
