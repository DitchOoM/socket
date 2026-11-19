package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.stream.ReadChunk
import com.ditchoom.socket.quic.core.stream.StreamIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress

/**
 * Reads out everything quiche holds for one stream at the end, up to the peer's FIN or reset, into buffers of the
 * connection's injected factory. Inside, at the end only; allocates.
 */
internal class EndHold(
    private val io: StreamIo,
    private val factory: BufferFactory,
) {
    fun stream(id: QuicStreamId): HeldStream {
        val chunks = ArrayList<PlatformBuffer>()
        while (true) {
            val chunk = factory.allocate(ReadChunk.MAX_BYTES)
            val address =
                when (val at = NativeBytes.addressOf(chunk)) {
                    is ReadAddress.At -> at.address
                    is ReadAddress.NotAddressable -> {
                        chunk.freeNativeMemory()
                        return HeldStream(chunks, HeldEnd.Open)
                    }
                }
            val step = io.recv(id, address, ReadChunk.MAX_BYTES)
            val read = step.fold(more = { it }, last = { it }, nothingYet = { 0 }, gone = { 0 }, reset = { 0 }, error = { 0 })
            if (read > 0) {
                chunk.position(0)
                chunk.setLimit(read)
                chunks.add(chunk)
            } else {
                chunk.freeNativeMemory()
            }
            val next =
                step.fold(
                    more = { if (it == 0) HoldStep.Open else HoldStep.More },
                    last = { HoldStep.Finished },
                    nothingYet = { HoldStep.Open },
                    gone = { HoldStep.Open },
                    reset = { HoldStep.Reset },
                    error = { HoldStep.Open },
                )
            when (next) {
                HoldStep.More -> Unit
                HoldStep.Open -> return HeldStream(chunks, HeldEnd.Open)
                HoldStep.Finished -> return HeldStream(chunks, HeldEnd.Finished)
                HoldStep.Reset -> return HeldStream(chunks, HeldEnd.Reset)
            }
        }
    }

    /** Where holding a stream stands after one read: more to read, or the peer's side as it stood. */
    private enum class HoldStep { More, Open, Finished, Reset }
}
