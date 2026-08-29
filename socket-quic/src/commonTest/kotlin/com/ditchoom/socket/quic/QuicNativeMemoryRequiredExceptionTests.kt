package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The typed rejection a write produces for a buffer without native memory (#502) must say, in its
 * message, everything a caller needs to fix the call site: which write, the declared capability, and
 * where to allocate from. The live end-to-end proof — that every quiche backend actually throws it —
 * is `QuicServerTestSuite.writeRejectsABufferWithoutNativeMemoryAsTheCapabilitySays`; this pins the
 * message composition on every target this module builds, browsers included.
 */
class QuicNativeMemoryRequiredExceptionTests {
    private val required = QuicCapabilities(requiresNativeMemoryBuffers = true)

    @Test
    fun streamRejectionNamesTheStreamTheCapabilityAndTheFix() {
        val buffer = BufferFactory.managed().allocate(3)
        val e = QuicNativeMemoryRequiredException.forBuffer(QuicWriteTarget.Stream(4), buffer, required)
        assertIs<IllegalArgumentException>(e, "a caller-contract violation, not a peer event")
        assertEquals(QuicWriteTarget.Stream(4), e.target)
        assertEquals(required, e.capabilities)
        val message = e.message ?: ""
        assertTrue(message.contains("QuicByteStream.write on stream 4"), message)
        assertTrue(message.contains("requiresNativeMemoryBuffers=true"), message)
        assertTrue(message.contains("QuicScope.bufferFactory"), message)
        assertTrue(message.contains("BufferFactory.deterministic()"), message)
        assertTrue(message.contains("Nothing was sent"), message)
        val bufferType = buffer::class.simpleName ?: "ReadBuffer"
        assertTrue(message.contains(bufferType), "the message must name the buffer's type: $message")
    }

    @Test
    fun datagramRejectionNamesTheDatagramSend() {
        val buffer = BufferFactory.managed().allocate(3)
        val e = QuicNativeMemoryRequiredException.forBuffer(QuicWriteTarget.Datagram, buffer, required)
        assertEquals(QuicWriteTarget.Datagram, e.target)
        assertTrue((e.message ?: "").contains("datagramChannel().send"), e.message)
    }

    @Test
    fun noneIsARealClaimThatAnyBufferIsAccepted() {
        assertEquals(false, QuicCapabilities.None.requiresNativeMemoryBuffers)
        assertEquals(QuicCapabilities(), QuicCapabilities.None)
    }
}
