package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.udp.SendBatch

/**
 * Where an endpoint's serve collects its output. Between serves an outbox holds only what its socket refused, so no
 * datagram of one socket is ever in a batch another socket sends. The loop thread only.
 */
@LoopOnly
internal sealed interface Outbox {
    /** The batch this serve collects into and sends from. */
    fun batch(): SendBatch

    /** After a serve sent what its socket took: what is left in [batch] waits for the socket to be writable. */
    fun served()

    /** Whether datagrams wait for the socket to be writable. */
    fun holds(): Boolean

    /** The endpoint left its loop: what it holds is dropped and its batch, if it borrowed one, goes back. */
    fun left()
}

/** A batch only one endpoint collects into: a server's, whose one socket carries every connection. */
@LoopOnly
internal class OwnBatch(
    private val batch: SendBatch,
) : Outbox {
    override fun batch(): SendBatch = batch

    override fun served() = Unit

    override fun holds(): Boolean = batch.pending > 0

    override fun left() = batch.took(batch.pending)
}

/** What a client endpoint holds between serves. */
private sealed interface Kept {
    /** Nothing: its next serve collects into the loop's batch. */
    data object Nothing : Kept

    /** Datagrams its socket refused, in a batch of the loop's that is this endpoint's until they are out. */
    class Refused(
        val batch: SendBatch,
    ) : Kept
}

/**
 * A client endpoint's outbox: its serves collect into the loop's one batch, which every endpoint of the loop shares, one
 * serve at a time. Datagrams the socket refused leave the shared batch with the endpoint: it keeps the whole batch, and
 * the loop collects into a spare from then on, so the next endpoint never finds them. Once they are out the batch goes
 * back to the loop's spares.
 */
@LoopOnly
internal class SharedBatch(
    private val loop: LoopSend,
) : Outbox {
    private var kept: Kept = Kept.Nothing

    override fun batch(): SendBatch =
        when (val now = kept) {
            is Kept.Nothing -> loop.batch()
            is Kept.Refused -> now.batch
        }

    override fun served() {
        when (val now = kept) {
            is Kept.Nothing -> {
                if (loop.batch().pending == 0) return
                kept =
                    when (val handed = loop.handOver()) {
                        is HandOver.Kept -> Kept.Refused(handed.batch)
                        is HandOver.NoSpare -> Kept.Nothing
                    }
            }
            is Kept.Refused ->
                if (now.batch.pending == 0) {
                    loop.takeBack(now.batch)
                    kept = Kept.Nothing
                }
        }
    }

    override fun holds(): Boolean =
        when (val now = kept) {
            is Kept.Nothing -> false
            is Kept.Refused -> true
        }

    override fun left() {
        when (val now = kept) {
            is Kept.Nothing -> Unit
            is Kept.Refused -> {
                now.batch.took(now.batch.pending)
                loop.takeBack(now.batch)
                kept = Kept.Nothing
            }
        }
    }
}

/** What [LoopSend.handOver] answers. */
internal sealed interface HandOver {
    /** The batch with the refused datagrams is the endpoint's; the loop collects into a spare. */
    class Kept(
        val batch: SendBatch,
    ) : HandOver

    /** No spare could be made: the refused datagrams were dropped, and quiche sends them again once it finds them lost. */
    data object NoSpare : HandOver
}

/** What a [SpareSource] answers. */
internal sealed interface SpareMade {
    class Made(
        val batch: SendBatch,
    ) : SpareMade

    data object Refused : SpareMade
}

/** Makes a batch like the loop's first, for an endpoint that keeps the loop's batch. */
internal fun interface SpareSource {
    fun make(): SpareMade
}

/**
 * The batch a client loop's endpoints collect into, one serve at a time, and the spares that replace it while endpoints
 * keep what their sockets refused. Spares are made on the first refusal that needs one and kept for the loop's life, so
 * the loop holds as many batches as endpoints ever waited for writable sockets at once, plus one. The loop thread only,
 * and [free] once it stopped.
 */
@LoopOnly
internal class LoopSend(
    /** The batch the loop starts with: any thread may read which it is, to view its memory once. */
    val first: SendBatch,
    private val spares: SpareSource,
) {
    private var current = first
    private val idle = ArrayList<SendBatch>()
    private val all = arrayListOf(first)

    /** Datagrams dropped because no spare could be made: diagnostics. */
    var dropped = 0L
        private set

    fun batch(): SendBatch = current

    /** [batch] holds datagrams its socket refused: it is the caller's now, and the loop collects into a spare. */
    fun handOver(): HandOver {
        val refused = current
        current =
            if (idle.isNotEmpty()) {
                idle.removeAt(idle.size - 1)
            } else {
                when (val made = spares.make()) {
                    is SpareMade.Made -> made.batch.also { all.add(it) }
                    is SpareMade.Refused -> {
                        dropped += refused.pending
                        refused.took(refused.pending)
                        return HandOver.NoSpare
                    }
                }
            }
        return HandOver.Kept(refused)
    }

    /** A batch [handOver] gave out is empty again. */
    fun takeBack(batch: SendBatch) {
        idle.add(batch)
    }

    /** Batches the loop holds, the one it collects into among them. */
    fun held(): Int = all.size

    /** Frees every batch's memory: only once the loop stopped and nobody sends from them. */
    fun free() {
        for (batch in all) batch.storage.free()
        all.clear()
        idle.clear()
    }
}
