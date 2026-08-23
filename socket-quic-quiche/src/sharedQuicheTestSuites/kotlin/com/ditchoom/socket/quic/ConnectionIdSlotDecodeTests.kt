package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The connection-ID readback buffer must decode to the ids quiche actually wrote — every slot, at the
 * right offset.
 *
 * ## Why this is worth its own suite
 * `quiche_conn_source_ids` and `quiche_conn_retired_scid_iter` both fill a flat buffer of
 * [RETIRED_SCID_SLOT_BYTES]-wide slots, each a length byte followed by that many id bytes. Getting the
 * offset wrong there is **silent**: a mis-decoded key is not a crash or a corrupt packet, it is a key
 * that matches no datagram — which is indistinguishable, from every other vantage point in the system,
 * from "the peer never used that CID". A routing table fed such keys keeps routing everything it
 * should have dropped and reports nothing wrong.
 *
 * That is not hypothetical. [ConnectionIdKey.from] reads by **absolute index and ignores the buffer's
 * position**, so the retired-id path that #441 added — which positioned the buffer at a slot, read the
 * length byte, then handed the buffer and length on — snapshotted the first slot's *length byte*
 * followed by the first id's leading bytes, for every slot including the first. The unregistration it
 * performed therefore removed a key nothing was routed under, and the retired CID kept routing. No test
 * covered the bytes: the stub's drain deliberately yields nothing, and the end-to-end suites assert on
 * connection survival, which the #445 quiche patch also provides.
 *
 * These run against a hand-built buffer, so they are about the decode and nothing else, and they fail
 * loudly for exactly the mistake that failed silently.
 */
class ConnectionIdSlotDecodeTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** Lay [ids] out exactly as a backend does: [RETIRED_SCID_SLOT_BYTES] per slot, length-prefixed. */
    private fun slotsOf(ids: List<List<Int>>) =
        bufferFactory.allocate(ids.size * RETIRED_SCID_SLOT_BYTES).apply {
            ids.forEachIndexed { index, id ->
                position(index * RETIRED_SCID_SLOT_BYTES)
                writeByte(id.size.toByte())
                id.forEach { writeByte(it.toByte()) }
            }
            resetForRead()
        }

    private fun key(vararg bytes: Int): ConnectionIdKey {
        val buf = bufferFactory.allocate(bytes.size)
        bytes.forEach { buf.writeByte(it.toByte()) }
        buf.resetForRead()
        return ConnectionIdKey.from(buf, offset = 0, length = bytes.size)
    }

    /**
     * **The regression.** Three ids in three slots must decode to those three ids — not to three
     * copies of something read off the front of the buffer.
     */
    @Test
    fun everySlotDecodesToItsOwnConnectionId() {
        val slots = slotsOf(listOf(listOf(1, 2, 3, 4), listOf(0xAA, 0xBB), listOf(9, 8, 7, 6, 5)))
        try {
            val decoded = decodeConnectionIdSlots(slots, 3)
            assertEquals(
                setOf(key(1, 2, 3, 4), key(0xAA, 0xBB), key(9, 8, 7, 6, 5)),
                decoded,
                "a slot was decoded from the wrong offset — the resulting key matches no datagram, which " +
                    "looks exactly like a CID the peer never used and unroutes nothing",
            )
        } finally {
            slots.freeNativeMemory()
        }
    }

    /**
     * The single-slot case, called out separately because it is the one the off-by-one could most
     * easily have survived: with one id the buffer's front *is* that slot, and only the length byte
     * stands between "right" and "shifted by one".
     */
    @Test
    fun aSingleSlotDoesNotIncludeItsOwnLengthByte() {
        val slots = slotsOf(listOf(listOf(7, 7, 7, 7)))
        try {
            assertEquals(
                setOf(key(7, 7, 7, 7)),
                decodeConnectionIdSlots(slots, 1),
                "the decoded id begins at the slot's length byte instead of one past it",
            )
        } finally {
            slots.freeNativeMemory()
        }
    }

    /**
     * A slot whose length byte is out of range is skipped without taking the good slots with it. The
     * readback is our own buffer coming back out of quiche, not peer-framed input, so there is nothing
     * to report — but a decoder that threw, or that stopped at the first bad slot, would drop live
     * routes over it.
     */
    @Test
    fun anOutOfRangeLengthSkipsOnlyItsOwnSlot() {
        val slots = slotsOf(listOf(listOf(1, 2), emptyList(), listOf(3, 4)))
        try {
            val decoded = decodeConnectionIdSlots(slots, 3)
            assertEquals(setOf(key(1, 2), key(3, 4)), decoded, "a zero-length slot took its neighbours with it")
        } finally {
            slots.freeNativeMemory()
        }
    }

    /** A backend that yielded nothing decodes to nothing — never to a phantom key from a zeroed slot. */
    @Test
    fun anEmptyReadbackDecodesToNoIds() {
        val slots = slotsOf(listOf(listOf(1, 2, 3)))
        try {
            assertTrue(decodeConnectionIdSlots(slots, 0).isEmpty(), "a zero count decoded a key anyway")
        } finally {
            slots.freeNativeMemory()
        }
    }
}
