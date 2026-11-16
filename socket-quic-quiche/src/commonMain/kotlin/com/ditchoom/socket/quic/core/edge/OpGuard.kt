package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
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

private sealed interface Bound {
    data object None : Bound

    class Armed(
        val node: DeadlineNode,
    ) : Bound
}

/** One parked operation's deadline: armed when the operation parks, disarmed when it settles. Inside only. */
@Confined
internal class OpDeadline(
    val bound: Duration,
) {
    private var armed: Bound = Bound.None

    /** Arms [expire] for the bound from now, unless the bound is infinite. */
    fun arm(
        inside: Inside,
        expire: Runnable,
    ) {
        if (!bound.isFinite()) return
        armed = Bound.Armed(inside.arm(Nanos(inside.now().value + bound.inWholeNanoseconds), expire))
    }

    fun disarm() {
        when (val now = armed) {
            is Bound.Armed -> now.node.disarm()
            is Bound.None -> Unit
        }
        armed = Bound.None
    }
}
