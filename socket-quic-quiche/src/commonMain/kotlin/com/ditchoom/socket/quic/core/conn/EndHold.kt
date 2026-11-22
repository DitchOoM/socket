package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.stream.ReadChunk
import com.ditchoom.socket.quic.core.stream.StreamIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress

/**
 * Reads out everything quiche holds for one stream at the end, up to the peer's FIN or reset (with its code), into buffers of the
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
            step.fold(
                more = { if (it == 0) return HeldStream(chunks, HeldEnd.Open) },
                last = { return HeldStream(chunks, HeldEnd.Finished) },
                nothingYet = { return HeldStream(chunks, HeldEnd.Open) },
                gone = { return HeldStream(chunks, HeldEnd.Open) },
                reset = { code -> return HeldStream(chunks, HeldEnd.Reset(code)) },
                error = { return HeldStream(chunks, HeldEnd.Open) },
            )
        }
    }
}
