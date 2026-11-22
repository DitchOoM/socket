package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import kotlin.time.Duration

/** How long each read under [policy] may wait. */
internal fun deadlineOf(policy: ReadPolicy): Duration =
    when (policy) {
        is ReadPolicy.Bounded -> policy.deadline
        is ReadPolicy.UntilClosed -> Duration.INFINITE
    }

/** How long each write under [policy] may wait. */
internal fun deadlineOf(policy: WritePolicy): Duration =
    when (policy) {
        is WritePolicy.Bounded -> policy.deadline
        is WritePolicy.UntilClosed -> Duration.INFINITE
    }
