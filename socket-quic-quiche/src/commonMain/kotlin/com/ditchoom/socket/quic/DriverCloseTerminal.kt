package com.ditchoom.socket.quic

import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * The one way a driver-backed connection says "this connection has ended" to a caller waiting on it.
 *
 * Every backend needs this at the same two places — a stream open that finds the command channel
 * closed, and an accept parked on the incoming-stream channel when it closes — so it is written once:
 * an expression repeated per backend is how one half comes to be missing it on every backend while
 * the other half has it.
 *
 * The reason is [QuicheDriver.closeReasonOr]'s, never one invented here: a terminal that made up its
 * own reason would type-check into the same shape while telling the caller nothing.
 */
internal fun QuicheDriver.connectionClosed(): QuicCloseException =
    QuicCloseException(
        closeReasonOr(QuicError.NoError),
        "connection closed",
        attribution = closeAttribution(),
    )

/**
 * [QuicConnection.acceptStream] for every backend — a receive that reports the connection's close
 * instead of the channel's.
 *
 * `cleanup()` ends teardown with `incomingStreams.close()`, which resumes whatever is parked here.
 * A bare `receive()` would hand that caller `ClosedReceiveChannelException`: a control-flow signal
 * wearing an error type, carrying no reason, and indistinguishable between "the peer closed cleanly
 * and there will be no more streams" and "the driver died". Whether it fires depends on teardown
 * winning a race against the accept loop's cancellation, so it presents as a flake — but the wrong
 * report is there on every close, race or not.
 *
 * Deliberately **not** applied to `streams()`: a [kotlinx.coroutines.flow.Flow] ending is already the
 * idiomatic way to say "no more streams", and `consumeAsFlow()` completes rather than throws, so no
 * caller is handed a coroutines internal there. Making it throw instead would change what the
 * HTTP/3 collectors observe.
 */
internal suspend fun QuicheDriver.acceptIncomingStream(): QuicByteStream =
    try {
        incomingStreams.receive()
    } catch (_: ClosedReceiveChannelException) {
        throw connectionClosed()
    }
