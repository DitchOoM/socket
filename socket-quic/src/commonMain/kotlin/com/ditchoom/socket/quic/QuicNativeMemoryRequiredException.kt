package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managedMemoryAccess

/**
 * Where a rejected outbound buffer was headed — the two data-plane writes a [QuicScope] offers.
 *
 * Sealed rather than a nullable stream id: a datagram has no stream, and `null` standing in for
 * "datagram" would be a meaning-carrying nullable.
 */
sealed interface QuicWriteTarget {
    /** A [QuicByteStream.write] on the stream with this id. */
    data class Stream(
        val streamId: Long,
    ) : QuicWriteTarget

    /** A `datagramChannel().send` (RFC 9221) on the connection. */
    data object Datagram : QuicWriteTarget
}

/**
 * A write was handed a buffer without native memory on a connection whose
 * [QuicCapabilities.requiresNativeMemoryBuffers] is `true`.
 *
 * This is a **caller-contract violation** — an [IllegalArgumentException] — not a peer event: the
 * peer never saw anything, nothing was enqueued to the engine, and the stream and connection are
 * exactly as they were. Contrast [QuicStreamException] (the peer aborted a stream) and
 * [QuicCloseException] (the connection ended). The fix is at the call site: allocate the buffer from
 * [QuicScope.bufferFactory] or `BufferFactory.deterministic()`, as [message] spells out.
 *
 * Thrown by every quiche-backed connection on every platform. It is the typed replacement for the
 * `NullPointerException` that `QuicheDriver.streamWrite`'s `nativeMemoryAccess!!` used to throw on
 * Kotlin/Native for a `BufferFactory.Default` buffer (#502) — a precondition that lived only in the
 * crash.
 *
 * [target] says which write, [capabilities] is the connection's declaration that made the buffer
 * unacceptable — so a handler can log the claim alongside the violation without re-reading the scope.
 */
class QuicNativeMemoryRequiredException(
    val target: QuicWriteTarget,
    val capabilities: QuicCapabilities,
    message: String,
) : IllegalArgumentException(message) {
    companion object {
        /**
         * Build the rejection for [buffer], describing what kind of buffer it was so the message names
         * the factory situation: a managed heap buffer (`BufferFactory.Default` on Linux Kotlin/Native,
         * `BufferFactory.managed()` / `BufferFactory.wrap(ByteArray)` everywhere) reads differently from
         * an opaque buffer type that exposes neither kind of memory.
         */
        fun forBuffer(
            target: QuicWriteTarget,
            buffer: ReadBuffer,
            capabilities: QuicCapabilities,
        ): QuicNativeMemoryRequiredException {
            val where =
                when (target) {
                    is QuicWriteTarget.Stream -> "QuicByteStream.write on stream ${target.streamId}"
                    is QuicWriteTarget.Datagram -> "datagramChannel().send"
                }
            val bufferType = buffer::class.simpleName ?: "ReadBuffer"
            val situation =
                if (buffer.managedMemoryAccess != null) {
                    "$bufferType — a managed heap buffer, which is what BufferFactory.Default allocates on " +
                        "Linux Kotlin/Native and what BufferFactory.managed() / BufferFactory.wrap(ByteArray) " +
                        "allocate on every platform"
                } else {
                    "$bufferType — a buffer type that exposes no native memory address"
                }
            return QuicNativeMemoryRequiredException(
                target,
                capabilities,
                "$where was handed a buffer without native memory ($situation), but this connection " +
                    "declares QuicCapabilities(requiresNativeMemoryBuffers=${capabilities.requiresNativeMemoryBuffers}): " +
                    "the QUIC engine reads the buffer's raw address and cannot take a heap buffer on any platform. " +
                    "Nothing was sent. Allocate outbound buffers from QuicScope.bufferFactory (BufferFactory.network() " +
                    "unless TransportConfig.bufferFactory overrode it) or BufferFactory.deterministic(), and free them " +
                    "once the write returns — e.g. bufferFactory.allocate(n).use { out -> …; stream.write(out) }.",
            )
        }
    }
}
