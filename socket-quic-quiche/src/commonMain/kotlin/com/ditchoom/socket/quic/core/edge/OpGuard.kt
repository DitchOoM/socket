package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferOverflowException

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
