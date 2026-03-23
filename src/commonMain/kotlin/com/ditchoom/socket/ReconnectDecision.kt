package com.ditchoom.socket

import kotlin.jvm.JvmInline
import kotlin.time.Duration

/**
 * Result of [ReconnectionClassifier.classify]: retry after a delay, or give up.
 */
sealed interface ReconnectDecision {
    @JvmInline
    value class RetryAfter(
        val delay: Duration,
    ) : ReconnectDecision

    data object GiveUp : ReconnectDecision
}
