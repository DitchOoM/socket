package com.ditchoom.socket.quic.core.edge

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
