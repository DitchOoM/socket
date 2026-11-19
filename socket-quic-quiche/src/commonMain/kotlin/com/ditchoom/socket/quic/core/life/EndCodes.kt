package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.QuicError

/** Whether an end is one a recording should be kept for. */
internal enum class EndKind { Normal, Abnormal }

/** The code a flight record stores for this end. */
internal fun ConnEnd.flightCode(): Int =
    when (this) {
        is ConnEnd.ByPeer -> 1
        is ConnEnd.ByLocal -> 2
        is ConnEnd.IdleTimeout -> 3
        is ConnEnd.HandshakeTimedOut -> 4
        is ConnEnd.SocketFailed -> 5
        is ConnEnd.ProcessingFailed -> 6
        is ConnEnd.CallerFailed -> 7
        is ConnEnd.LoopFailed -> 8
        is ConnEnd.LoopStopped -> 9
        is ConnEnd.ServerClosed -> 10
        is ConnEnd.NoErrorRecorded -> 11
        is ConnEnd.Released -> 12
        is ConnEnd.NetworkLost -> 13
        is ConnEnd.LocalAddressGone -> 14
        is ConnEnd.ServerClosedUnsettled -> 15
        is ConnEnd.NoUsablePath -> 16
        is ConnEnd.HandshakeSendRefused -> 17
    }

/** The wire error this end carries, as a number, for the flight record; zero when it carries none. */
internal fun ConnEnd.errorCode(): Long =
    when (this) {
        is ConnEnd.ByPeer -> error.code
        is ConnEnd.ByLocal -> error.code
        is ConnEnd.IdleTimeout, is ConnEnd.HandshakeTimedOut, is ConnEnd.HandshakeSendRefused, is ConnEnd.SocketFailed,
        is ConnEnd.ProcessingFailed,
        is ConnEnd.CallerFailed, is ConnEnd.LoopFailed, is ConnEnd.LoopStopped, is ConnEnd.ServerClosed,
        is ConnEnd.NoErrorRecorded, is ConnEnd.Released, is ConnEnd.NetworkLost, is ConnEnd.LocalAddressGone,
        is ConnEnd.ServerClosedUnsettled, is ConnEnd.NoUsablePath,
        -> 0L
    }

/** A close both sides agreed on with no error is normal; every other end is kept. */
internal fun ConnEnd.kind(): EndKind =
    when (this) {
        is ConnEnd.ByPeer -> if (error is QuicError.NoError) EndKind.Normal else EndKind.Abnormal
        is ConnEnd.ByLocal -> if (error is QuicError.NoError) EndKind.Normal else EndKind.Abnormal
        is ConnEnd.IdleTimeout, is ConnEnd.HandshakeTimedOut, is ConnEnd.HandshakeSendRefused, is ConnEnd.SocketFailed,
        is ConnEnd.ProcessingFailed,
        is ConnEnd.CallerFailed, is ConnEnd.LoopFailed, is ConnEnd.NoErrorRecorded, is ConnEnd.NetworkLost,
        is ConnEnd.LocalAddressGone, is ConnEnd.ServerClosedUnsettled, is ConnEnd.NoUsablePath,
        -> EndKind.Abnormal
        is ConnEnd.LoopStopped, is ConnEnd.ServerClosed, is ConnEnd.Released -> EndKind.Normal
    }
