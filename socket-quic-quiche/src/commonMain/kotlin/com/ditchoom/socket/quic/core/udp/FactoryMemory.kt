package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer

/** Memory made from a connection's factory as [NativeBytes], or how making it failed. */
internal sealed interface MadeMemory {
    class Made(
        val bytes: NativeBytes,
    ) : MadeMemory

    /** The factory threw [failure], or made memory without an address; nothing was kept, so nothing leaks. */
    class Failed(
        val failure: Throwable,
    ) : MadeMemory
}

/** The factory made memory without an address, which the connect had checked it would not. */
internal class MemoryNotAddressable : Throwable()

/** [size] bytes from this factory, with their address; a throw or memory without an address is [MadeMemory.Failed]. */
internal fun BufferFactory.nativeBytes(size: Int): MadeMemory {
    val buffer =
        try {
            allocate(size)
        } catch (failure: Throwable) {
            return MadeMemory.Failed(failure)
        }
    return when (val addressed = addressOf(buffer)) {
        is Addressing.Done ->
            when (val at = addressed.at) {
                is Addressed.Native -> MadeMemory.Made(at.bytes)
                is Addressed.NotAddressable -> MadeMemory.Failed(MemoryNotAddressable()).also { buffer.freeNativeMemory() }
            }
        is Addressing.Failed -> MadeMemory.Failed(addressed.failure).also { buffer.freeNativeMemory() }
    }
}

/** [NativeBytes.of] for [buffer], or what it threw. */
private sealed interface Addressing {
    class Done(
        val at: Addressed,
    ) : Addressing

    class Failed(
        val failure: Throwable,
    ) : Addressing
}

private fun addressOf(buffer: PlatformBuffer): Addressing =
    try {
        Addressing.Done(NativeBytes.of(buffer))
    } catch (failure: Throwable) {
        Addressing.Failed(failure)
    }
