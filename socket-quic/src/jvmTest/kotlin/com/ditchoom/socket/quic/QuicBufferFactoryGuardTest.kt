package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.managed
import com.ditchoom.socket.ConnectionOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Spike (hardening): [ConnectionOptions.quicBufferFactory] / [requireNativeMemory] reject a heap factory
 * with a clear message instead of letting it NPE deep inside the quiche binding.
 *
 * QUIC hands buffer *addresses* to native code on every platform (the JVM included — the FFM/JNI binding
 * is address-based, not array-based), so a managed/heap factory was never usable; before this guard the
 * failure was a raw `NullPointerException` on `nativeMemoryAccess!!`. Pure + fast: no native lib, no I/O.
 */
class QuicBufferFactoryGuardTest {
    @Test
    fun defaultResolvesToNativeNetworkFactory() {
        // The untouched default must keep working — it resolves the Default sentinel to network().
        val resolved = ConnectionOptions().quicBufferFactory()
        assertSame(BufferFactory.network(), resolved, "Default must resolve to the native network() factory")
    }

    @Test
    fun explicitNativeFactoryIsHonored() {
        val native = BufferFactory.deterministic()
        assertSame(native, ConnectionOptions(bufferFactory = native).quicBufferFactory())
    }

    @Test
    fun managedHeapFactoryFailsFastWithExplanatoryMessage() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ConnectionOptions(bufferFactory = BufferFactory.managed()).quicBufferFactory()
            }
        assertTrue(
            ex.message!!.contains("native-memory", ignoreCase = true) &&
                ex.message!!.contains("network()"),
            "guard message should name the constraint and the fix: ${ex.message}",
        )
    }

    @Test
    fun requireNativeMemoryReturnsTheReceiverWhenNative() {
        val native = BufferFactory.deterministic()
        assertSame(native, native.requireNativeMemory())
    }

    @Test
    fun networkFactoryIsItselfNative() {
        // Sanity: the QUIC default must satisfy its own guard.
        assertEquals(BufferFactory.network(), BufferFactory.network().requireNativeMemory())
    }
}
