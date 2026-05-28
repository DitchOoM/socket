package com.ditchoom.socket.quic.netctrl

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.WireSize
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed TCP framing for the generated codecs.
 * Wire format: [2-byte big-endian length][codec payload]
 */
object NetCtrlFraming {
    private const val BACK_PATCH_FALLBACK_SIZE = 8192

    fun <T> send(
        out: OutputStream,
        encoder: Encoder<T>,
        message: T,
    ) {
        val context = EncodeContext.Empty
        val capacity =
            when (val ws = encoder.wireSize(message, context)) {
                is WireSize.Exact -> ws.bytes
                WireSize.BackPatch -> BACK_PATCH_FALLBACK_SIZE
            }
        val buf = BufferFactory.Default.allocate(capacity)
        encoder.encode(buf, message, context)
        buf.resetForRead()
        val size = buf.remaining()
        val dos = DataOutputStream(out)
        dos.writeShort(size)
        val bytes = buf.readByteArray(size)
        dos.write(bytes)
        dos.flush()
    }

    fun <T> recv(
        inp: InputStream,
        decoder: Decoder<T>,
    ): T {
        val dis = DataInputStream(inp)
        val size = dis.readUnsignedShort()
        val bytes = ByteArray(size)
        dis.readFully(bytes)
        val buffer: ReadBuffer = BufferFactory.Default.wrap(bytes)
        return decoder.decode(buffer, DecodeContext.Empty)
    }
}
