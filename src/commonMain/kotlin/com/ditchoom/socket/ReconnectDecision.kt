package com.ditchoom.socket

import kotlin.jvm.JvmInline
import kotlin.time.Duration

sealed interface ReconnectDecision {
    @JvmInline
    value class RetryAfter(
        val delay: Duration,
    ) : ReconnectDecision

    data object GiveUp : ReconnectDecision
}
