package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.pool.FixedPool

/**
 * Reads what quiche holds for a stream nobody will read, into a borrowed [DrainSlot], and drops it. Reading is what
 * hands the peer its flow-control credit back and, at the peer's FIN or reset, lets quiche let the stream go. Inside
 * the connection only.
 */
@Confined
internal class StreamDrain(
    private val io: StreamIo,
    private val slots: FixedPool<DrainSlot>,
) {
    /**
     * Drops what quiche holds for [id] now: the peer's side's ending when the drain met it, else [Side.Open]. With no
     * slot free quiche keeps the data, and the stream stays readable for the next wake.
     */
    fun drop(id: QuicStreamId): Side =
        slots.take({ slot ->
            try {
                dropInto(id, slot)
            } finally {
                slots.give(slot)
            }
        }, { Side.Open })

    private fun dropInto(
        id: QuicStreamId,
        slot: DrainSlot,
    ): Side {
        while (true) {
            val read =
                io.recv(id, slot.address, slot.capacity).fold(
                    more = { bytes -> if (bytes == 0) Step.Waiting else Step.More },
                    last = { Step.Finished },
                    nothingYet = { Step.Waiting },
                    gone = { Step.Waiting },
                    reset = { Step.Reset },
                    error = { Step.Refused },
                )
            when (read) {
                Step.More -> Unit
                Step.Waiting -> return Side.Open
                Step.Finished -> return Side.Finished
                Step.Reset -> return Side.Reset
                Step.Refused -> return Side.Refused
            }
        }
    }

    /** One read's outcome: more may be read now, quiche holds nothing more for now, or the peer's side ended. */
    private enum class Step { More, Waiting, Finished, Reset, Refused }
}
