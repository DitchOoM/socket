package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes

/**
 * Every piece of native memory one client connection, or one server, holds from the caller's factory, freed together
 * once it is released. A factory whose memory cannot carry datagrams fails the connect or the serve, typed, before any
 * socket exists. Not thread-safe: it has one owner at a time, the setup until its loop takes it over (see [ClientSetup]
 * and [SetupOwner]), and only that owner calls [freeAll].
 */
internal class EdgeMemory(
    private val factory: BufferFactory,
) {
    private val owned = ArrayList<NativeBytes>()
    private val claims = ArrayList<MemoryHold.Claim>()

    /** Releases [claim], taken by the caller, with the rest of this memory. */
    fun releasing(claim: MemoryHold.Claim) {
        claims.add(claim)
    }

    fun native(size: Int): NativeBytes {
        val buffer = factory.allocate(size)
        return when (val addressed = NativeBytes.of(buffer)) {
            is Addressed.Native -> addressed.bytes.also { owned.add(it) }
            is Addressed.NotAddressable -> raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.HandshakeFailed)
        }
    }

    fun factory(): BufferFactory = factory

    fun freeAll() {
        for (bytes in owned) bytes.free()
        owned.clear()
        for (claim in claims) claim.release()
        claims.clear()
    }
}
