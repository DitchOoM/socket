package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration

/**
 * Abandon a peer-initiated [stream] whose read expired the caller's [deadline] (#477 on the client,
 * #495 on the server), returning the named failure for whoever was waiting on the stream. Shared by
 * both roles: they face the same peer behaviour and the same trap.
 *
 * The trap: `QuicheDriver` implements a read deadline as `withTimeout`, whose
 * `TimeoutCancellationException` IS a `CancellationException`, and a `launch` child completing with one
 * is *cancelled, not failed* — the parent is never told, an arm placed after a
 * `catch (e: CancellationException)` never sees it, and a generic `catch (t: Throwable)` would not help
 * because the cancellation arm claims it first. So the caller's arm for this exception must come BEFORE
 * any cancellation handling.
 *
 * But not by type alone: a parent's `withTimeout` cancels the caller WITH its own
 * `TimeoutCancellationException`, so the exception cannot say whose deadline expired. The job can — a
 * read deadline leaves the caller active, a cancellation does not — which is why the first thing this
 * does is [ensureActive]: a caller that was cancelled gets its cancellation rethrown, and a teardown is
 * never reported to the peer as a stall. The check lives here rather than in each catch arm so that
 * the next call site cannot forget it.
 *
 * The peer is told: RFC 9114 §4.1.1 makes `H3_REQUEST_CANCELLED` the code for a request either side
 * gives up on (§4.6 for a client abandoning a push stream), sent as RESET_STREAM + STOP_SENDING — a
 * plain `close()` would FIN only our send side and leave the peer's half open until the connection
 * ends. Quietly, because the connection may already be gone; the abandonment is per-stream either way.
 */
internal suspend fun abandonStalledStream(
    stream: QuicByteStream,
    deadline: Duration,
    cause: TimeoutCancellationException,
): Http3StreamException {
    currentCoroutineContext().ensureActive()
    val failure = Http3StreamException(Http3Violation.PeerStreamDeadlineExpired(stream.streamId, deadline, cause))
    stream.resetQuietly(failure.errorCode)
    return failure
}

/** Reset this stream with [errorCode], ignoring a failure if the connection/stream is already gone. */
internal suspend fun QuicByteStream.resetQuietly(errorCode: Long) {
    try {
        reset(errorCode)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Already torn down — nothing to reset.
    }
}
