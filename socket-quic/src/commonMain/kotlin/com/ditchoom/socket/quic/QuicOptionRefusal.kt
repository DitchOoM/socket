package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QlogTarget
import kotlin.time.Duration

/** The [QuicOptions] property a [QuicOptionRefusal] is about. */
enum class QuicOptionName { Migration, StandbyLink, StreamOpenPolicy, Trace }

/**
 * Why an option value was refused: the engine cannot honour it, so the connect or serve that carries it fails before any
 * socket opens, instead of running without it.
 */
sealed interface QuicOptionRefusal {
    /** The option the refused value was set on. */
    val option: QuicOptionName

    /** [StandbyLink.KeepCellularReady] on a platform with a standby radio this engine does not hold; use [StandbyLink.OnDemand]. */
    data object StandbyRadioNotHeld : QuicOptionRefusal {
        override val option: QuicOptionName get() = QuicOptionName.StandbyLink
    }

    /** [StreamOpenPolicy.Bounded] with [deadline]: a stream open waits until it is granted or the caller cancels. */
    data class StreamOpenNotBounded(
        val deadline: Duration,
    ) : QuicOptionRefusal {
        override val option: QuicOptionName get() = QuicOptionName.StreamOpenPolicy
    }

    /** A [QlogTarget.Budgeted] qlog: use [QlogTarget.File], or the `QUIC_QLOG_DIR` environment. */
    data class QlogNotBudgeted(
        val target: QlogTarget.Budgeted,
    ) : QuicOptionRefusal {
        override val option: QuicOptionName get() = QuicOptionName.Trace
    }
}

/** A connect or serve whose options carry a value its engine cannot honour, for [refusal]; raised before any socket opens. */
open class QuicOptionRefusedException(
    refusal: QuicOptionRefusal,
    message: String = "${refusal.option} cannot be honoured here: $refusal",
) : UnsupportedOperationException(message) {
    /** What was refused. */
    open val refusal: QuicOptionRefusal = refusal
}
