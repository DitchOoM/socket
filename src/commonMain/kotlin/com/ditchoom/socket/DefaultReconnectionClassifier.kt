package com.ditchoom.socket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Default reconnection classifier with exponential backoff.
 *
 * Non-recoverable errors (TLS failures, DNS failures) produce [ReconnectDecision.GiveUp].
 * All other errors produce [ReconnectDecision.RetryAfter] with increasing delay.
 *
 * Call [reset] when a connection succeeds to restart the backoff sequence.
 */
class DefaultReconnectionClassifier(
    private val initialDelay: Duration = 100.milliseconds,
    private val maxDelay: Duration = 15.seconds,
    private val factor: Double = 2.0,
) : ReconnectionClassifier {
    private var currentDelay: Duration = initialDelay

    override suspend fun classify(error: Throwable): ReconnectDecision {
        if (isNonRecoverable(error)) return ReconnectDecision.GiveUp
        val delay = currentDelay
        currentDelay = (currentDelay * factor).coerceAtMost(maxDelay)
        return ReconnectDecision.RetryAfter(delay)
    }

    fun reset() {
        currentDelay = initialDelay
    }

    companion object {
        /**
         * Returns `true` if the error is non-recoverable — retrying with the same
         * parameters will always fail.
         *
         * Exposed as a static utility so protocol libraries can call it directly
         * without instantiating a classifier.
         */
        fun isNonRecoverable(error: Throwable): Boolean =
            when (error) {
                is SSLHandshakeFailedException -> true
                is SSLProtocolException -> true
                is SocketUnknownHostException -> true
                else -> false
            }
    }
}
