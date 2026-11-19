package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Native memory is a type, not a cast: a factory whose buffers have no address is answered with
 * [Addressed.NotAddressable] where the buffer is first wrapped, before any socket exists. The edge turns that
 * answer into the typed connect failure (step 3); nothing below it can be handed such a buffer.
 */
class NativeBytesTests {
    @Test
    fun aHeapBufferHasNoAddressAndIsTold() {
        assertEquals(Addressed.NotAddressable, NativeBytes.of(BufferFactory.managed().allocate(64)))
    }

    @Test
    fun aNativeBufferIsAddressedAndKeepsItsCapacity() {
        val native = assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(128)))
        assertNotEquals(0L, native.bytes.address)
        assertTrue(native.bytes.capacity >= 128, "the network factory may round the capacity up")
        native.bytes.free()
    }

    @Test
    fun theJdkStackViewsNativeBytesAndRefusesNothingItWasHanded() {
        val native = assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(64)))
        val view = assertIs<JdkBacked.Backed>(JdkBytes.of(native.bytes))
        assertEquals(native.bytes.capacity, view.bytes.view.capacity())
        native.bytes.free()
    }
}
