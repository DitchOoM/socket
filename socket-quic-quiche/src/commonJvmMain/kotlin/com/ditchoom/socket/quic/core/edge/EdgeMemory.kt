package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.JdkBacked
import com.ditchoom.socket.quic.core.udp.JdkBytes
import com.ditchoom.socket.quic.core.udp.NativeBytes

/**
 * Every piece of native memory one client connection, or one server, holds from the caller's factory, freed together
 * once it is released. A factory whose memory cannot carry datagrams fails the connect or the serve, typed, before any
 * socket exists.
 */
internal class EdgeMemory(
    private val factory: BufferFactory,
) {
    private val owned = ArrayList<NativeBytes>()

    fun native(size: Int): NativeBytes {
        val buffer = factory.allocate(size)
        return when (val addressed = NativeBytes.of(buffer)) {
            is Addressed.Native -> addressed.bytes.also { owned.add(it) }
            is Addressed.NotAddressable -> raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.HandshakeFailed)
        }
    }

    /** The JDK view a channel reads and writes [bytes] through. */
    fun jdk(bytes: NativeBytes): JdkBytes =
        when (val backed = JdkBytes.of(bytes)) {
            is JdkBacked.Backed -> backed.bytes
            is JdkBacked.NotJdkBacked -> raise(Raised.FactoryNotNative(bytes.owner), QuicCloseCircumstance.HandshakeFailed)
        }

    fun factory(): BufferFactory = factory

    fun freeAll() {
        for (bytes in owned) bytes.free()
        owned.clear()
    }
}
