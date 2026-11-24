@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.DatagramReceive
import com.ditchoom.socket.quic.QuicDatagramBatch
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.Datagrams
import kotlinx.coroutines.currentCoroutineContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A connection's datagram receiver is free; [RECEIVER_TAKEN] while one receive runs. */
internal const val RECEIVER_FREE = 0
internal const val RECEIVER_TAKEN = 1

/**
 * The batch receives of one connection's datagrams: each takes the receiver ([receiving]) and gives it back when it
 * returns, and is lent into the one [LentBatch] the receiver reuses. A receive awaits its answer in its own frame, so a
 * receive that suspends makes one frame, one [BatchOp] and its answer word.
 */
internal class BatchReceives(
    private val connection: CoreConnection,
    private val conn: Conn,
    private val receiving: AtomicInt,
) {
    private val batch = LentBatch()

    /** One batch, [block] run on it once it was lent. */
    suspend fun <R> once(block: (QuicDatagramBatch) -> R): DatagramReceive<R> {
        when (val claim = claimed()) {
            is Claim.Refused -> return claim.why
            is Claim.Taken -> Unit
        }
        try {
            val context = currentCoroutineContext()
            val op = BatchOp(batch, conn)
            conn.submit(op, connection.placeOf(context))
            return when (val answer = op.hand.await(context) { op.cancelled() }) {
                is BatchAnswer.Taken -> {
                    val count = batch.show()
                    try {
                        DatagramReceive.Batch(block(batch), count)
                    } finally {
                        batch.hide()
                    }
                }
                is BatchAnswer.Ended -> DatagramReceive.NotReceived.Unavailable.ConnectionEnded(publicEndOf(answer.end))
                is BatchAnswer.Failed -> raiseFailure(answer.failure, answer.during)
                is BatchAnswer.Cancelled -> raiseCancelled(context)
            }
        } finally {
            receiving.store(RECEIVER_FREE)
        }
    }

    /** Batch after batch, [each] datagram in turn, in one frame, until no datagram will arrive. */
    suspend fun each(each: (ReadBuffer) -> Unit): DatagramReceive.NotReceived {
        when (val claim = claimed()) {
            is Claim.Refused -> return claim.why
            is Claim.Taken -> Unit
        }
        try {
            val context = currentCoroutineContext()
            while (true) {
                val op = BatchOp(batch, conn)
                conn.submit(op, connection.placeOf(context))
                when (val answer = op.hand.await(context) { op.cancelled() }) {
                    is BatchAnswer.Taken -> {
                        val count = batch.show()
                        try {
                            for (at in 0 until count) each(batch[at])
                        } finally {
                            batch.hide()
                        }
                    }
                    is BatchAnswer.Ended -> return DatagramReceive.NotReceived.Unavailable.ConnectionEnded(publicEndOf(answer.end))
                    is BatchAnswer.Failed -> raiseFailure(answer.failure, answer.during)
                    is BatchAnswer.Cancelled -> raiseCancelled(context)
                }
            }
        } finally {
            receiving.store(RECEIVER_FREE)
        }
    }

    /** The receiver taken for this receive, or why not. */
    private fun claimed(): Claim {
        when (conn.datagrams()) {
            is Datagrams.Off -> return NOT_NEGOTIATED
            is Datagrams.On -> Unit
        }
        return if (receiving.compareAndSet(RECEIVER_FREE, RECEIVER_TAKEN)) Claim.Taken else BUSY
    }
}

/** Whether a receive took the receiver, or [Refused] with the receive's answer of why not. */
private sealed interface Claim {
    data object Taken : Claim

    class Refused(
        val why: DatagramReceive.NotReceived,
    ) : Claim
}

private val NOT_NEGOTIATED = Claim.Refused(DatagramReceive.NotReceived.Unavailable.NotNegotiated)
private val BUSY = Claim.Refused(DatagramReceive.NotReceived.ReceiverBusy)
