package com.ditchoom.socket.quic

/**
 * All possible QUIC errors modeled as a sealed hierarchy.
 * No stringly-typed errors — every error has a concrete type.
 * Maps to RFC 9000 §20 transport error codes and application/platform errors.
 */
sealed interface QuicError {
    /** The error code as defined by RFC 9000, or -1 for non-transport errors. */
    val code: Long

    // Transport errors (RFC 9000 §20.1)
    data object NoError : QuicError {
        override val code: Long = 0x0
    }

    data class InternalError(
        val detail: String,
    ) : QuicError {
        override val code: Long = 0x1
    }

    data object ConnectionRefused : QuicError {
        override val code: Long = 0x2
    }

    data object FlowControlError : QuicError {
        override val code: Long = 0x3
    }

    data object StreamLimitError : QuicError {
        override val code: Long = 0x4
    }

    data class StreamStateError(
        val streamId: QuicStreamId,
    ) : QuicError {
        override val code: Long = 0x5
    }

    data object FinalSizeError : QuicError {
        override val code: Long = 0x6
    }

    data object FrameEncodingError : QuicError {
        override val code: Long = 0x7
    }

    data class TransportParameterError(
        val detail: String,
    ) : QuicError {
        override val code: Long = 0x8
    }

    data object ConnectionIdLimitError : QuicError {
        override val code: Long = 0x9
    }

    data object ProtocolViolation : QuicError {
        override val code: Long = 0xA
    }

    data object InvalidToken : QuicError {
        override val code: Long = 0xB
    }

    /**
     * Transport-level APPLICATION_ERROR (RFC 9000 §20.1, code 0x0c).
     *
     * Signals an application-caused close in a context that only permits a *transport*
     * CONNECTION_CLOSE frame (type 0x1c) — typically during the handshake, before the
     * application can use a type-0x1d frame. Distinct from [ApplicationError], which carries
     * an application-protocol-defined close code sent in a type-0x1d frame.
     */
    data object TransportApplicationError : QuicError {
        override val code: Long = 0xC
    }

    data class CryptoBufferExceeded(
        val detail: String,
    ) : QuicError {
        override val code: Long = 0xD
    }

    data object KeyUpdateError : QuicError {
        override val code: Long = 0xE
    }

    data object AeadLimitReached : QuicError {
        override val code: Long = 0xF
    }

    data object NoViablePath : QuicError {
        override val code: Long = 0x10
    }

    /**
     * A transport error code this library does not recognize — reserved, currently unassigned,
     * or defined by a future QUIC extension. Preserves the original wire [code] instead of
     * collapsing it to [InternalError], so the numeric value survives decoding for diagnostics
     * and forward compatibility.
     */
    data class UnknownTransport(
        override val code: Long,
    ) : QuicError

    /** TLS alert mapped to QUIC crypto error (0x100 + alert code). */
    data class CryptoError(
        val tlsAlert: Int,
    ) : QuicError {
        override val code: Long = 0x100L + tlsAlert
    }

    /** Application-level error (opaque code defined by the application protocol). */
    data class ApplicationError(
        val applicationCode: Long,
    ) : QuicError {
        override val code: Long = applicationCode
    }

    /** Platform-specific error that doesn't map to a QUIC transport code. */
    data class PlatformError(
        val cause: Throwable,
    ) : QuicError {
        override val code: Long = -1
    }

    /**
     * The connection closed because the idle timeout elapsed (RFC 9000 §10.1). This is a *local* event —
     * no CONNECTION_CLOSE frame is sent on the wire — so it has no transport [code] (-1). Surfaced as a
     * distinct reason so an idle close (or a stalled handshake that idle-times-out, e.g. a cross-stack
     * QUIC interop failure) reports meaningfully instead of masquerading as a clean [NoError] shutdown.
     */
    data object IdleTimeout : QuicError {
        override val code: Long = -1
    }

    /**
     * Human-readable one-line description including the numeric wire [code] in hex — for log/exception
     * messages. The structured value stays the sealed type; this is only its rendering (e.g.
     * `ProtocolViolation (0xa)`, `ApplicationError(applicationCode=256) (0x100)`).
     */
    fun describe(): String = "$this (0x${code.toString(16)})"

    companion object {
        fun fromTransportCode(code: Long): QuicError =
            when (code) {
                0x0L -> NoError
                0x1L -> InternalError("transport error 0x1")
                0x2L -> ConnectionRefused
                0x3L -> FlowControlError
                0x4L -> StreamLimitError
                0x5L -> StreamStateError(QuicStreamId(0))
                0x6L -> FinalSizeError
                0x7L -> FrameEncodingError
                0x8L -> TransportParameterError("transport error 0x8")
                0x9L -> ConnectionIdLimitError
                0xAL -> ProtocolViolation
                0xBL -> InvalidToken
                0xCL -> TransportApplicationError
                0xDL -> CryptoBufferExceeded("transport error 0xD")
                0xEL -> KeyUpdateError
                0xFL -> AeadLimitReached
                0x10L -> NoViablePath
                in 0x100L..0x1FFL -> CryptoError((code - 0x100L).toInt())
                else -> UnknownTransport(code)
            }
    }
}
