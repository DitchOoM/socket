package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.core.sched.Caught
import com.ditchoom.socket.quic.core.stream.SendRefusal
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlinx.coroutines.Runnable

/**
 * The operation-tier containment site: what one operation's attempt throws (an `Exception`) fails that operation's
 * caller through [failed] and nothing else; an `Error` is not contained and reaches the connection's own site.
 */
internal inline fun guarded(
    failed: (Exception) -> Unit,
    body: () -> Unit,
) {
    try {
        body()
    } catch (failure: Exception) {
        failed(failure)
    }
}

/**
 * Runs [body], the caller's own block on a connection: the close it meets once the connection ended ([ended]) is the
 * end's, which the next operation reports, so it does not fail [body]'s own caller; anything else it throws does.
 */
internal inline fun outlivingTheEnd(
    ended: () -> Boolean,
    body: () -> Unit,
) {
    try {
        body()
    } catch (closed: QuicCloseException) {
        if (!ended()) raise(Raised.Thrown(closed), QuicCloseCircumstance.Ended)
    }
}

/**
 * The handler-tier containment site: runs [body], a stream handler of the application's; whatever it throws, `Error`
 * included, reaches [failed], which decides how far it goes by its tier, and nothing propagates out of here.
 */
internal inline fun handlerCode(
    failed: (Throwable) -> Unit,
    body: () -> Unit,
) {
    try {
        body()
    } catch (failure: Throwable) {
        failed(failure)
    }
}

/**
 * The pool-tier containment site, which a handler pool runs every step and its observation sink under: what [code]
 * throws, `Error` included, is caught and answered, so the pool thread goes on.
 */
internal fun poolContained(code: Runnable): Caught =
    try {
        code.run()
        Caught.Clean
    } catch (failure: Throwable) {
        Caught.Threw(Thrown(failure))
    }

/** Runs [body], which is the caller's own code (a codec); what it throws is turned into the answer by [failed]. */
internal inline fun <R> userCode(
    failed: (Exception) -> R,
    body: () -> R,
): R =
    try {
        body()
    } catch (failure: Exception) {
        failed(failure)
    }

/** Runs [body], which writes into a buffer; a write past its end is answered by [overflow]. */
internal inline fun <R> overflowing(
    overflow: () -> R,
    body: () -> R,
): R =
    try {
        body()
    } catch (full: BufferOverflowException) {
        overflow()
    } catch (full: IllegalArgumentException) {
        // A position seek past the limit is signalled differently per platform; the same answer.
        overflow()
    }

/** The failure a refused stream send is: past the close point, or after the end. */
internal fun SendRefusal.raised(): Raised =
    when (this) {
        is SendRefusal.PastClose -> Raised.PastClose(end)
        is SendRefusal.Ended -> Raised.ConnEnded(end)
    }
