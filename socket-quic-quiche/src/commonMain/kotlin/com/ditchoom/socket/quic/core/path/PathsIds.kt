package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.RETIRED_SCID_SLOT_BYTES
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.life.RetiredId
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlin.jvm.JvmInline
import kotlin.random.Random

/** How many ids [IdIssuer.issue] handed quiche. */
@JvmInline
internal value class Issued(
    val count: Int,
)

/**
 * Hands quiche the source connection ids its peer may use for a new path (quiche issues none itself): what a client
 * needs to probe a path from a new local address, and what the server needs to follow it there. Each id is
 * [QUIC_ISSUED_CONN_ID_LEN] bytes, as the server routes them. The scratch the ids are written through is the
 * issuer's own, made on the first issue and freed with the connection.
 */
internal class IdIssuer(
    private val factory: BufferFactory,
) {
    private val scratch = ArrayList<NativeBytes>(1)

    /** As many fresh ids drawn from [random] as quiche may hand the peer now. */
    fun issueAll(
        q: QuicheConnRef,
        random: Random,
    ): Issued {
        var issued = 0
        while (q.api.connScidsLeft(q.handle) > 0L) {
            val id = SpareId(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong())
            if (issue(q, id).count == 0) break
            issued++
        }
        return Issued(issued)
    }

    /** Hands quiche [id]; nothing when quiche refuses it or no native scratch could be made. */
    fun issue(
        q: QuicheConnRef,
        id: SpareId,
    ): Issued {
        val bytes =
            when (val held = held()) {
                is Addressed.Native -> held.bytes
                is Addressed.NotAddressable -> return Issued(0)
            }
        // Big-endian, as the server's routes read an id's bytes into its two halves.
        val out = bytes.owner
        out.resetForWrite()
        out.writeLong(id.c0)
        out.writeLong(id.c1)
        out.writeLong(id.token0)
        out.writeLong(id.token1)
        val at = bytes.address
        val seq = q.api.connNewScid(q.handle, at + CID, QUIC_ISSUED_CONN_ID_LEN, at + TOKEN, false, at + SEQ)
        return Issued(if (seq >= 0) 1 else 0)
    }

    fun free() {
        for (bytes in scratch) bytes.free()
        scratch.clear()
    }

    private fun held(): Addressed {
        if (scratch.isNotEmpty()) return Addressed.Native(scratch[0])
        val made = NativeBytes.of(factory.allocate(BYTES))
        when (made) {
            is Addressed.Native -> scratch.add(made.bytes)
            is Addressed.NotAddressable -> Unit
        }
        return made
    }

    companion object {
        private const val CID = 0
        private const val TOKEN = 16
        private const val SEQ = 32

        /** The size of the scratch an issuer writes ids through. */
        const val BYTES = 40
    }
}

/**
 * Reads the ids the peer retired out of quiche, which drops them as it reads: the native slots they are written into
 * are the connection's own, made when the first id is retired and freed with the connection.
 */
internal class RetiredReader(
    private val factory: BufferFactory,
) {
    private val scratch = ArrayList<NativeBytes>(1)

    /** The ids of [QUIC_ISSUED_CONN_ID_LEN] bytes the peer retired since the last read, at most [SLOTS] of them. */
    fun read(q: QuicheConnRef): List<RetiredId> {
        if (q.api.connRetiredScids(q.handle) == 0) return emptyList()
        val bytes =
            when (val held = held()) {
                is Addressed.Native -> held.bytes
                is Addressed.NotAddressable -> return emptyList()
            }
        val yielded = minOf(q.api.connDrainRetiredScids(q.handle, bytes.address, SLOTS), SLOTS)
        val ids = ArrayList<RetiredId>(yielded)
        for (slot in 0 until yielded) {
            val at = slot * RETIRED_SCID_SLOT_BYTES
            if (bytes.byteAt(at) == QUIC_ISSUED_CONN_ID_LEN) ids.add(RetiredId(bigEndian(bytes, at + 1), bigEndian(bytes, at + 1 + WORD)))
        }
        return ids
    }

    fun free() {
        for (bytes in scratch) bytes.free()
        scratch.clear()
    }

    private fun held(): Addressed {
        if (scratch.isNotEmpty()) return Addressed.Native(scratch[0])
        val made = NativeBytes.of(factory.allocate(SLOTS * RETIRED_SCID_SLOT_BYTES))
        when (made) {
            is Addressed.Native -> scratch.add(made.bytes)
            is Addressed.NotAddressable -> Unit
        }
        return made
    }

    private fun bigEndian(
        bytes: NativeBytes,
        from: Int,
    ): Long {
        var value = 0L
        for (i in from until from + WORD) value = (value shl BITS) or bytes.byteAt(i).toLong()
        return value
    }

    private companion object {
        const val SLOTS = 32
        const val WORD = 8
        const val BITS = 8
    }
}
