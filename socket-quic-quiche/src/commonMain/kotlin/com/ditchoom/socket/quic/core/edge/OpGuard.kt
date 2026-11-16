package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable
import kotlin.time.Duration

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

/** What an unarmed [OpDeadline] holds in place of a node. */
private object NotArmed : DisposableHandle {
    override fun dispose() = Unit
}

/** One parked operation's deadline: armed when the operation parks, disarmed when it settles. Inside only. */
@Confined
internal class OpDeadline(
    val bound: Duration,
) {
    /** The armed node, which is its own handle; [NotArmed] when none is. */
    private var armed: DisposableHandle = NotArmed

    /** Arms [expire] for the bound from now, unless the bound is infinite. */
    fun arm(
        inside: Inside,
        expire: Runnable,
    ) {
        if (!bound.isFinite()) return
        armed = inside.arm(Nanos(inside.now().value + bound.inWholeNanoseconds), expire)
    }

    fun disarm() {
        armed.dispose()
        armed = NotArmed
    }
}
