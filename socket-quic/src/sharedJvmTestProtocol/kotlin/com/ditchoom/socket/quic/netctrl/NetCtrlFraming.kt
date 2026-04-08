package com.ditchoom.socket.quic.netctrl

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.encodeToBuffer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed TCP framing for the generated codecs.
 * Wire format: [2-byte big-endian length][codec payload]
 */
object NetCtrlFraming {
    fun <T> send(
        out: OutputStream,
        encoder: Encoder<T>,
        message: T,
    ) {
        val encoded = encoder.encodeToBuffer(message, BufferFactory.Default)
        val size = encoded.remaining()
        val dos = DataOutputStream(out)
        dos.writeShort(size)
        val bytes = encoded.readByteArray(size)
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
        return decoder.decode(buffer)
    }
}
