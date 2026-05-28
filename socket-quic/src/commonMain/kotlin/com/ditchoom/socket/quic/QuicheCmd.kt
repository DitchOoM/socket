package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.CompletableDeferred

/**
 * Commands processed sequentially by the [QuicheDriver] coroutine.
 *
 * quiche is single-threaded — the driver is the only coroutine that touches it.
 * All parameters use value classes or sealed types — no raw Long mixing possible.
 */
sealed interface QuicheCmd {
    /** Feed an incoming UDP packet to quiche. [buf] ownership transfers to the driver (freed after processing). */
    class RecvPacket(
        val buf: PlatformBuffer,
        val len: Int,
    ) : QuicheCmd

    /** Allocate the next stream ID and create a [StreamSlot]. */
    class OpenStream(
        val result: CompletableDeferred<StreamSlot>,
    ) : QuicheCmd

    /** Read data from a QUIC stream. */
    class StreamRecv(
        val streamId: Long,
        val addr: Long,
        val bufLen: Int,
        val result: CompletableDeferred<StreamRecvResult>,
    ) : QuicheCmd

    /** Write data to a QUIC stream. */
    class StreamSend(
        val streamId: Long,
        val addr: Long,
        val bufLen: Int,
        val fin: Boolean,
        val result: CompletableDeferred<Int>,
    ) : QuicheCmd

    /** Gracefully close the connection. */
    class Close(
        val error: QuicError,
        val result: CompletableDeferred<Unit>,
    ) : QuicheCmd
}
