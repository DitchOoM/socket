@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.Volatile

/**
 * A server-side [AddressedDatagramChannel] decorator that can **withhold one inbound datagram** and
 * deliver it later, reproducing the arrival a real network produces by reordering: a packet sent on
 * a path the peer has since migrated away from, overtaken by the RETIRE_CONNECTION_ID that travels
 * the new (faster) path.
 *
 * Withholding rather than dropping is the whole point. A synthetic packet cannot exercise the defect
 * — quiche resolves the destination CID *after* `decrypt_pkt`, so anything not sealed with the live
 * 1-RTT keys is discarded first — and a *replayed* one cannot either, because the duplicate check on
 * `recv_pkt_num` also precedes the CID lookup and returns `Done` with or without the fix. Only a
 * genuine packet the peer really sent, that quiche has never seen, reaches
 * `get_or_create_recv_path_id`. This holds exactly that packet aside and hands it over on a later
 * `receive()`, with its original source address, so it arrives as the network would have delivered it.
 *
 * Wired in via [QuicPortBinding.Shared], the same production seam a demultiplexed port uses, so
 * nothing about the server under test is test-only.
 *
 * Single-writer by construction: only the server's reader coroutine calls [receive], so the
 * `@Volatile` fields need no atomics — the test coroutine only ever reads them or sets a flag.
 */
internal class HoldbackDatagramChannel(
    private val delegate: AddressedDatagramChannel,
) : AddressedDatagramChannel by delegate {
    @Volatile
    private var lastDcid: ByteArray? = null

    @Volatile
    private var holdTarget: ByteArray? = null

    @Volatile
    private var held: DatagramReadResult.Received? = null

    @Volatile
    private var releaseRequested = false

    private val heldSignal = CompletableDeferred<ByteArray>()
    private val deliveredSignal = CompletableDeferred<Unit>()

    /** Destination CID of the most recent short-header datagram the server was handed. */
    fun lastShortHeaderDcid(): ByteArray? = lastDcid

    /** Withhold the next short-header datagram whose destination CID is [dcid]. */
    fun holdNextDatagramFor(dcid: ByteArray) {
        holdTarget = dcid.copyOf()
    }

    /** Suspends until a datagram has been withheld; returns the destination CID it carries. */
    suspend fun awaitHeld(): ByteArray = heldSignal.await()

    /**
     * Suspends until the withheld datagram has actually been handed to the server. Without this a
     * green run could not distinguish "the connection survived the late packet" from "the late packet
     * was never delivered", which is the difference between a regression guard and a vacuous pass.
     */
    suspend fun awaitDelivered(): Unit = deliveredSignal.await()

    /**
     * Deliver the withheld datagram on the next `receive()`. The reader coroutine is parked in
     * `delegate.receive()` at this point, so the handover happens when the next datagram arrives —
     * which is what the caller's follow-up write provides.
     */
    fun release() {
        releaseRequested = true
    }

    /**
     * Frees a datagram still being withheld when the server shuts down. Nothing else owns it — the
     * server never received it — so without this a test that fails before releasing leaks the payload,
     * which is exactly the kind of accumulated echo leak that primed the #401 corruption.
     */
    override fun close() {
        held?.datagram?.payload?.freeNativeMemory()
        held = null
        delegate.close()
    }

    override suspend fun receive(): DatagramReadResult {
        val pending = held
        if (releaseRequested && pending != null) {
            held = null
            releaseRequested = false
            deliveredSignal.complete(Unit)
            return pending
        }
        while (true) {
            val result = delegate.receive()
            if (result is DatagramReadResult.Received) {
                val dcid = shortHeaderDcid(result.datagram.payload)
                if (dcid != null) {
                    lastDcid = dcid
                    val target = holdTarget
                    if (target != null && held == null && dcid.contentEquals(target)) {
                        held = result
                        holdTarget = null
                        heldSignal.complete(dcid)
                        // Swallowed: the server never sees this datagram until release().
                        continue
                    }
                }
            }
            return result
        }
    }

    /**
     * The destination CID of a 1-RTT (short-header) packet, or null for a long-header one. A short
     * header carries no CID length, so the reader must already know it — for this server every source
     * CID it issues is [QUIC_MAX_CONN_ID_LEN] bytes (`generateScid`). Reads are position-neutral: the
     * server reads the same buffer afterwards by native address and `remaining()`.
     */
    private fun shortHeaderDcid(payload: PlatformBuffer): ByteArray? {
        val start = payload.position()
        return try {
            if (payload.remaining() < 1 + QUIC_MAX_CONN_ID_LEN) {
                null
            } else if (payload.readByte().toInt() and 0x80 != 0) {
                null // long header (Initial/Handshake/Retry) — its DCID is length-prefixed instead
            } else {
                payload.readByteArray(QUIC_MAX_CONN_ID_LEN)
            }
        } finally {
            payload.position(start)
        }
    }
}
