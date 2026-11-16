package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.core.life.QuicheConnRef
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

    private companion object {
        const val CID = 0
        const val TOKEN = 16
        const val SEQ = 32
        const val BYTES = 40
    }
}
