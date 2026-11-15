package com.ditchoom.socket.quic

/**
 * A cancellation handler for a [kotlinx.coroutines.CancellableContinuation], whatever the cancellation's cause: kotlinx's
 * handler type carries the cause as a nullable, which stays at this boundary.
 */
internal abstract class OnCancel : (Throwable?) -> Unit {
    abstract fun cancelled()

    final override fun invoke(cause: Throwable?) = cancelled()
}
