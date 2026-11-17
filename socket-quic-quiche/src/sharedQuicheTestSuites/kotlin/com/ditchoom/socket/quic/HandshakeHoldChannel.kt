@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.Volatile

/**
 * A server-side hold that, while [holding], drops every inbound datagram carrying a QUIC version 1 Handshake packet —
 * coalesced or not — so the client's Finished never reaches the server and the server's handshake cannot complete.
 * Everything else passes, so a resuming client's Initial and 0-RTT packets arrive and the server runs with early data
 * and nothing confirmed. [release] stops dropping; the client's Handshake-space retransmission then completes the
 * handshake.
 */
internal interface HandshakeHold {
    /** Whether Handshake datagrams are being dropped right now. */
    val holding: Boolean

    /** Drop every Handshake datagram from here on, until [release]. */
    fun holdHandshakes()

    /** Stop dropping: the next Handshake datagram reaches the server. */
    fun release()

    /** Suspends until a Handshake datagram has been dropped: the client sent its Finished and the server did not get it. */
    suspend fun awaitFirstDrop()
}

/** The hold's state, shared by its channel and quiche-api forms. Written by the test alone. */
internal class HoldState : HandshakeHold {
    @Volatile
    private var hold: Hold = Hold.Passing

    private val firstDrop = CompletableDeferred<Unit>()

    override val holding: Boolean get() = hold is Hold.Dropping

    override fun holdHandshakes() {
        hold = Hold.Dropping
    }

    override fun release() {
        hold = Hold.Passing
    }

    override suspend fun awaitFirstDrop(): Unit = firstDrop.await()

    /** Whether a datagram of [length] bytes, read through [byteAt], is dropped; a drop is remembered. */
    fun drops(
        length: Int,
        byteAt: (Int) -> Int,
    ): Boolean {
        if (hold !is Hold.Dropping || !carriesHandshake(length, byteAt)) return false
        firstDrop.complete(Unit)
        return true
    }

    private sealed interface Hold {
        data object Passing : Hold

        data object Dropping : Hold
    }
}

/**
 * The hold on a server's [AddressedDatagramChannel], wired in through [QuicPortBinding.Shared], the production seam a
 * demultiplexed port uses. Only the server's reader coroutine calls [receive].
 */
internal class HandshakeHoldChannel(
    private val delegate: AddressedDatagramChannel,
    private val state: HoldState = HoldState(),
) : AddressedDatagramChannel by delegate,
    HandshakeHold by state {
    override suspend fun receive(): DatagramReadResult {
        while (true) {
            val result = delegate.receive()
            if (result is DatagramReadResult.Received) {
                val payload = result.datagram.payload
                val start = payload.position()
                if (state.drops(payload.limit() - start) { payload[start + it].toInt() and 0xff }) {
                    payload.freeNativeMemory()
                    continue
                }
            }
            return result
        }
    }
}

/** The hold at quiche's door: a server built on this api never hands its quiche a held datagram. */
internal class HandshakeHoldApi(
    private val inner: QuicheApi,
    private val state: HoldState = HoldState(),
) : QuicheApi by inner,
    HandshakeHold by state {
    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int {
        if (state.drops(bufLen) { UnsafeMemory.getByte(buf + it).toInt() and 0xff }) return bufLen
        return inner.connRecv(conn, buf, bufLen, recvInfo)
    }
}

/**
 * Walk a datagram's coalesced long-header packets (RFC 9000 §12.2, §17.2) and report whether one is a Handshake packet.
 * A short-header packet ends the walk: it is always last.
 */
private fun carriesHandshake(
    end: Int,
    byteAt: (Int) -> Int,
): Boolean {
    var at = 0
    while (at < end) {
        val first = byteAt(at)
        if (first and 0x80 == 0) return false
        val type = (first shr 4) and 0x03
        if (type == HANDSHAKE) return true
        var cursor = at + 5 // first byte + version
        cursor += 1 + byteAt(cursor) // DCID length + DCID
        cursor += 1 + byteAt(cursor) // SCID length + SCID
        if (type == INITIAL) {
            val (tokenLength, width) = varInt(cursor, byteAt)
            cursor += width + tokenLength.toInt()
        }
        if (type == RETRY) return false
        val (length, width) = varInt(cursor, byteAt)
        at = cursor + width + length.toInt()
    }
    return false
}

private fun varInt(
    at: Int,
    byteAt: (Int) -> Int,
): Pair<Long, Int> {
    val first = byteAt(at)
    val width = 1 shl (first shr 6)
    var value = (first and 0x3f).toLong()
    for (i in 1 until width) value = (value shl 8) or byteAt(at + i).toLong()
    return value to width
}

private const val INITIAL = 0
private const val HANDSHAKE = 2
private const val RETRY = 3
