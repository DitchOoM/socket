package com.ditchoom.socket

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.unwrapFully
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * ART regression guard for [compactCompat], reproducing the exact buffer the TLS path crashes on.
 *
 * `JvmTlsHandler` allocates via the caller's [BufferFactory]; the library/test default is
 * [BufferFactory.deterministic]. On Android that factory has no `Unsafe.invokeCleaner`, so it falls
 * back to `Unsafe.allocateMemory` and hands back an **array-less** native `DirectByteBuffer`
 * (`hb == null`). `java.nio.DirectByteBuffer.compact()` on libcore reaches a `System.arraycopy`
 * against that null array and throws `NullPointerException: src == null` — the crash that took down
 * every WSS handshake. (Plain `allocateDirect()` buffers are array-backed on ART and compact fine,
 * which is why only the deterministic/unsafe path trips.)
 *
 * This runs on the device (not the host JVM, where `deterministic()` uses `invokeCleaner` +
 * `allocateDirect` and compacts fine), so it fails if `compactCompat` ever regresses onto the
 * broken path, and its first case documents the underlying platform bug.
 */
@RunWith(AndroidJUnit4::class)
class CompactCompatAndroidTest {
    // Mirrors JvmTlsHandler.allocateBuffer: the deterministic factory → array-less DirectByteBuffer on ART.
    private fun deterministicDirect(capacity: Int): ByteBuffer =
        (BufferFactory.deterministic().allocate(capacity).unwrapFully() as BaseJvmBuffer).byteBuffer

    private fun seed(
        bb: ByteBuffer,
        filled: Int,
        consumed: Int,
    ) {
        bb.clear()
        for (i in 0 until filled) bb.put((i + 1).toByte())
        bb.flip()
        bb.position(consumed)
    }

    private fun frontBytes(
        bb: ByteBuffer,
        count: Int,
    ): ByteArray = ByteArray(count) { bb.get(it) }

    @Test
    fun stockCompactThrowsOnDeterministicBuffer() {
        // Documents the platform bug compactCompat routes around. If a future ART release fixes
        // DirectByteBuffer.compact() for array-less buffers, this flips and the helper can retire.
        val bb = deterministicDirect(32)
        seed(bb, filled = 20, consumed = 12)
        assertThrows(NullPointerException::class.java) { bb.compact() }
    }

    @Test
    fun compactCompatMovesRemainingBytesToFront() {
        val bb = deterministicDirect(32)
        seed(bb, filled = 20, consumed = 12) // 8 unread bytes: 13..20
        bb.compactCompat()
        assertEquals(8, bb.position())
        assertEquals(32, bb.limit())
        assertArrayEquals(ByteArray(8) { (it + 13).toByte() }, frontBytes(bb, 8))
    }

    @Test
    fun compactCompatOnFullyUnreadBufferIsIdentity() {
        val bb = deterministicDirect(16)
        seed(bb, filled = 10, consumed = 0)
        bb.compactCompat()
        assertEquals(10, bb.position())
        assertEquals(16, bb.limit())
        assertArrayEquals(ByteArray(10) { (it + 1).toByte() }, frontBytes(bb, 10))
    }
}
