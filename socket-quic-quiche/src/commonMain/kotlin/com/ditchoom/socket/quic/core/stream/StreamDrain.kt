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
            io.recv(id, slot.address, slot.capacity).fold(
                more = { bytes -> if (bytes == 0) return Side.Open },
                last = { return Side.Finished },
                nothingYet = { return Side.Open },
                gone = { return Side.Open },
                reset = { code -> return Side.Reset(code) },
                error = { return Side.Refused },
            )
        }
    }
}
