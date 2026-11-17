@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.udp.ReadAddress

/** The native address of [buffer], which quiche reads; a buffer with none ends the handshake with a typed failure. */
internal fun addressOf(buffer: com.ditchoom.buffer.PlatformBuffer): Long =
    when (
        val at =
            com.ditchoom.socket.quic.core.udp.NativeBytes
                .addressOf(buffer)
    ) {
        is ReadAddress.At -> at.address
        is ReadAddress.NotAddressable -> raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.HandshakeFailed)
    }

/** [text] as UTF-8 with a trailing NUL, for a C string argument. */
internal fun nullTerminated(
    text: String,
    factory: BufferFactory,
): com.ditchoom.buffer.PlatformBuffer =
    factory.allocate(text.length + 1).apply {
        writeString(text, Charset.UTF8)
        writeByte(0)
        resetForRead()
    }
