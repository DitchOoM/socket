@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

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
 * Only the server's reader coroutine calls [receive], so the ingress ring and the last-seen CID have
 * one writer; the hold state's two writers take turns (see [hold]), so nothing here needs atomics.
 *
 * Every datagram [receive] pulls is also recorded in a fixed ring ([describe]), so a test that stalls
 * can say what the server's ingress actually saw — which connection IDs, and which datagram was
 * withheld or released — instead of only that a deadline passed.
 */
internal class HoldbackDatagramChannel(
    private val delegate: AddressedDatagramChannel,
) : AddressedDatagramChannel by delegate {
    @Volatile
    private var lastDcid: ByteArray? = null

    /**
     * Where the one withheld datagram is in its life. Written by the test coroutine only on the
     * Idle→Armed and Withheld→ReleaseRequested edges and by the reader only on the other two, and the
     * test takes its edge only after observing the reader's previous one ([awaitHeld]), so the two
     * writers never race on the same edge.
     */
    @Volatile
    private var hold: Hold = Hold.Idle

    private val heldSignal = CompletableDeferred<ByteArray>()
    private val deliveredSignal = CompletableDeferred<Unit>()

    // The ingress ring: primitive slots written only by the reader coroutine, so recording costs no
    // allocation and no lock, and nothing in it can perturb the arrival order it is recording.
    private val ringDisposition = IntArray(RING_SIZE)
    private val ringDcidPrefix = IntArray(RING_SIZE)
    private val ringLength = IntArray(RING_SIZE)
    private val ringAtMillis = IntArray(RING_SIZE)
    private val createdAt = TimeSource.Monotonic.markNow()

    @Volatile
    private var ringCount = 0

    /** Destination CID of the most recent short-header datagram the server was handed. */
    fun lastShortHeaderDcid(): ByteArray? = lastDcid

    /** Withhold the next short-header datagram whose destination CID is [dcid]. */
    fun holdNextDatagramFor(dcid: ByteArray) {
        check(hold is Hold.Idle) { "a datagram is already being withheld: $hold" }
        hold = Hold.Armed(dcid.copyOf())
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
        val withheld = hold
        check(withheld is Hold.Withheld) { "nothing is withheld to release: $withheld" }
        hold = Hold.ReleaseRequested(withheld.datagram)
    }

    /**
     * Frees a datagram still being withheld when the server shuts down. Nothing else owns it — the
     * server never received it — so without this a test that fails before releasing leaks the payload,
     * which is exactly the kind of accumulated echo leak that primed the #401 corruption.
     */
    override fun close() {
        when (val h = hold) {
            is Hold.Withheld ->
                h.datagram.datagram.payload
                    .freeNativeMemory()
            is Hold.ReleaseRequested ->
                h.datagram.datagram.payload
                    .freeNativeMemory()
            Hold.Idle, is Hold.Armed, Hold.Delivered -> Unit
        }
        hold = Hold.Delivered
        delegate.close()
    }

    override suspend fun receive(): DatagramReadResult {
        val releasing = hold
        if (releasing is Hold.ReleaseRequested) {
            hold = Hold.Delivered
            val payload = releasing.datagram.datagram.payload
            record(Ingress.Released, dcidPrefix(shortHeaderDcid(payload)), payload.remaining())
            deliveredSignal.complete(Unit)
            return releasing.datagram
        }
        while (true) {
            val result = delegate.receive()
            if (result is DatagramReadResult.Received) {
                val length = result.datagram.payload.remaining()
                val dcid = shortHeaderDcid(result.datagram.payload)
                if (dcid == null) {
                    record(Ingress.LongHeader, 0, length)
                } else {
                    lastDcid = dcid
                    val armed = hold
                    if (armed is Hold.Armed && dcid.contentEquals(armed.target)) {
                        hold = Hold.Withheld(result)
                        record(Ingress.Withheld, dcidPrefix(dcid), length)
                        heldSignal.complete(dcid)
                        // Swallowed: the server never sees this datagram until release().
                        continue
                    }
                    record(Ingress.Passed, dcidPrefix(dcid), length)
                }
            }
            return result
        }
    }

    /**
     * The hold's own state and the newest [RING_SIZE] ingress records, oldest first: one line a stalled
     * test can put in its failure message. Read from the test coroutine while the reader may still be
     * writing, so the newest slot can be torn; every slot before it is settled.
     */
    fun describe(): String {
        val count = ringCount
        val first = maxOf(0, count - RING_SIZE)
        val entries =
            (first until count).joinToString(" ") { index ->
                val slot = index % RING_SIZE
                val dcid = ringDcidPrefix[slot].toUInt().toString(16).padStart(8, '0')
                "#$index@${ringAtMillis[slot]}ms:${Ingress.entries[ringDisposition[slot]]}(dcid=$dcid..,len=${ringLength[slot]})"
            }
        return "ingress: hold=$hold, last dcid=${dcidPrefix(lastDcid).toUInt().toString(16)}.., " +
            "$count datagrams, newest ${count - first}: [$entries]"
    }

    private fun record(
        disposition: Ingress,
        dcidPrefix: Int,
        length: Int,
    ) {
        val slot = ringCount % RING_SIZE
        ringDisposition[slot] = disposition.ordinal
        ringDcidPrefix[slot] = dcidPrefix
        ringLength[slot] = length
        ringAtMillis[slot] = createdAt.elapsedNow().inWholeMilliseconds.toInt()
        ringCount++
    }

    /** The one datagram this channel withholds, from being asked for to being handed over. */
    private sealed interface Hold {
        data object Idle : Hold

        class Armed(
            val target: ByteArray,
        ) : Hold {
            override fun toString(): String = "Armed(dcid=${dcidPrefix(target).toUInt().toString(16)}..)"
        }

        class Withheld(
            val datagram: DatagramReadResult.Received,
        ) : Hold {
            override fun toString(): String = "Withheld"
        }

        class ReleaseRequested(
            val datagram: DatagramReadResult.Received,
        ) : Hold {
            override fun toString(): String = "ReleaseRequested"
        }

        /** Handed to the server, or freed at close: nothing is withheld and nothing can be again. */
        data object Delivered : Hold
    }

    /** What [receive] did with one datagram. */
    private enum class Ingress { LongHeader, Passed, Withheld, Released }

    private companion object {
        const val RING_SIZE = 32
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

/** The first four bytes of [dcid] — enough to tell one connection's IDs apart in a record. */
private fun dcidPrefix(dcid: ByteArray?): Int =
    if (dcid == null || dcid.size < 4) {
        0
    } else {
        (dcid[0].toInt() and 0xff shl 24) or (dcid[1].toInt() and 0xff shl 16) or (dcid[2].toInt() and 0xff shl 8) or
            (dcid[3].toInt() and 0xff)
    }
