@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.Volatile

/**
 * A server-side [AddressedDatagramChannel] decorator that, while [holding], drops every inbound datagram
 * carrying a QUIC version 1 Handshake packet — coalesced or not — so the client's Finished never reaches
 * the server and the server's handshake cannot complete. Everything else passes, so a resuming client's
 * Initial and 0-RTT packets arrive and the server runs with early data and nothing confirmed.
 *
 * [release] stops dropping; the client's Handshake-space retransmission then completes the handshake.
 * Wired in through [QuicPortBinding.Shared], the production seam a demultiplexed port uses.
 *
 * Only the server's reader coroutine calls [receive]; the test writes [holding] alone.
 */
internal class HandshakeHoldChannel(
    private val delegate: AddressedDatagramChannel,
) : AddressedDatagramChannel by delegate {
    @Volatile
    private var hold: Hold = Hold.Passing

    private val firstDrop = CompletableDeferred<Unit>()

    /** Whether Handshake datagrams are being dropped right now. */
    val holding: Boolean get() = hold is Hold.Dropping

    /** Drop every Handshake datagram from here on, until [release]. */
    fun holdHandshakes() {
        hold = Hold.Dropping
    }

    /** Stop dropping: the next Handshake datagram reaches the server. */
    fun release() {
        hold = Hold.Passing
    }

    /** Suspends until a Handshake datagram has been dropped: the client sent its Finished and the server did not get it. */
    suspend fun awaitFirstDrop(): Unit = firstDrop.await()

    override suspend fun receive(): DatagramReadResult {
        while (true) {
            val result = delegate.receive()
            if (result is DatagramReadResult.Received && hold is Hold.Dropping && carriesHandshake(result.datagram.payload)) {
                result.datagram.payload.freeNativeMemory()
                firstDrop.complete(Unit)
                continue
            }
            return result
        }
    }

    private sealed interface Hold {
        data object Passing : Hold

        data object Dropping : Hold
    }

    /**
     * Walk the datagram's coalesced long-header packets (RFC 9000 §12.2, §17.2) and report whether one is
     * a Handshake packet. A short-header packet ends the walk: it is always last. Position-neutral.
     */
    private fun carriesHandshake(payload: PlatformBuffer): Boolean {
        val start = payload.position()
        val end = payload.limit()
        try {
            var at = start
            while (at < end) {
                val first = payload[at].toInt() and 0xff
                if (first and 0x80 == 0) return false
                val type = (first shr 4) and 0x03
                if (type == HANDSHAKE) return true
                var cursor = at + 5 // first byte + version
                cursor += 1 + (payload[cursor].toInt() and 0xff) // DCID length + DCID
                cursor += 1 + (payload[cursor].toInt() and 0xff) // SCID length + SCID
                if (type == INITIAL) {
                    val (tokenLength, width) = varInt(payload, cursor)
                    cursor += width + tokenLength.toInt()
                }
                if (type == RETRY) return false
                val (length, width) = varInt(payload, cursor)
                at = cursor + width + length.toInt()
            }
            return false
        } finally {
            payload.position(start)
        }
    }

    private fun varInt(
        payload: PlatformBuffer,
        at: Int,
    ): Pair<Long, Int> {
        val first = payload[at].toInt() and 0xff
        val width = 1 shl (first shr 6)
        var value = (first and 0x3f).toLong()
        for (i in 1 until width) value = (value shl 8) or (payload[at + i].toLong() and 0xff)
        return value to width
    }

    private companion object {
        const val INITIAL = 0
        const val HANDSHAKE = 2
        const val RETRY = 3
    }
}
