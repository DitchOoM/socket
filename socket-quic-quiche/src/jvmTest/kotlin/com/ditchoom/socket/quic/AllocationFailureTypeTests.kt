package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `calloc` failing inside `quiche_jni.c` returns 0, and the FFM backend throws [OutOfMemoryError] for
 * the same case. Both must fail the same typed way; before this the JNI path let the 0 reach
 * `QuicheRecvInfo`'s `require(handle != 0L)` and threw `IllegalArgumentException`.
 *
 * No caller ever dereferenced the 0 (the handle's `init` rejects it first), so this is about the
 * failure type, not memory safety. An allocation failure cannot be forced inside the C library from
 * here, hence the test drives the translation the backend applies to the C return value.
 */
class AllocationFailureTypeTests {
    @Test
    fun aNullFromCallocIsAnOutOfMemoryErrorNotAnIllegalArgument() {
        val e = assertFailsWith<OutOfMemoryError> { allocatedOrOutOfMemory(0L, "quiche_recv_info") }
        assert(e.message!!.contains("quiche_recv_info"))
    }

    @Test
    fun aRealHandlePassesThroughUntouched() {
        assertEquals(0x1000L, allocatedOrOutOfMemory(0x1000L, "quiche_send_info"))
    }

    @Test
    fun theHandleTypeStillRejectsNullForEveryOtherSource() {
        assertFailsWith<IllegalArgumentException> { QuicheRecvInfo(0L) }
    }
}
