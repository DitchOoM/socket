package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlin.concurrent.AtomicReference

/**
 * Where a socket keeps the bytes of a recv that completed for a reader that was cancelled before it resumed.
 *
 * Such a recv has already taken its bytes out of the socket, so dropping them loses data the peer sent and the
 * caller was never told about. They are kept for the next read, which returns them before it submits anything.
 * The shape is the QUIC driver's `salvageCancelledRecv`: a result that raced a cancellation goes to the owner of
 * the stream, and only an empty buffer is released.
 */
internal class RecvSalvage {
    private val slot = AtomicReference<Salvaged>(Salvaged.None)

    /** Keeps [buffer], whose first [bytes] bytes a recv wrote, for the next [take]. */
    fun hold(
        buffer: PlatformBuffer,
        bytes: Int,
    ) {
        buffer.position(bytes)
        buffer.resetForRead()
        // Reads of one socket are serialized and each starts with [take], so the slot is empty here.
        check(slot.compareAndSet(Salvaged.None, Salvaged.Held(buffer))) { "a salvaged read was never taken" }
    }

    /** Whether [buffer] is the one [hold] kept, so its owner must not free it. */
    fun isHolding(buffer: ReadBuffer): Boolean = (slot.value as? Salvaged.Held)?.buffer === buffer

    /** The salvaged bytes, handing ownership to the caller; the slot is empty afterwards. */
    fun take(): Salvaged = slot.getAndSet(Salvaged.None)

    /** Releases bytes nobody will read: the socket is gone. */
    fun discard() {
        (take() as? Salvaged.Held)?.buffer?.freeNativeMemory()
    }
}

/** What [RecvSalvage] holds. */
internal sealed interface Salvaged {
    data object None : Salvaged

    /** [buffer] is ready to read. */
    class Held(
        val buffer: PlatformBuffer,
    ) : Salvaged
}
