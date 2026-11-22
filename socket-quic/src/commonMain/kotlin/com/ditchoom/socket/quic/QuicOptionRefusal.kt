package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QlogTarget

/** The [QuicOptions] property a [QuicOptionRefusal] is about. */
enum class QuicOptionName { Migration, StandbyLink, Trace }

/**
 * Why an option value was refused: the engine cannot honour it, so the connect or serve that carries it fails before any
 * socket opens, instead of running without it.
 */
sealed interface QuicOptionRefusal {
    /** The option the refused value was set on. */
    val option: QuicOptionName

    /** [StandbyLink.KeepCellularReady]: this engine holds no standby link; use [StandbyLink.OnDemand]. */
    data object StandbyLinkNotHeld : QuicOptionRefusal {
        override val option: QuicOptionName get() = QuicOptionName.StandbyLink
    }

    /** A [QlogTarget.Budgeted] qlog, declared or named by a capture: use [QlogTarget.File], or the `QUIC_QLOG_DIR` environment. */
    data object QlogNotBudgeted : QuicOptionRefusal {
        override val option: QuicOptionName get() = QuicOptionName.Trace
    }
}

/** A connect or serve whose options carry a value its engine cannot honour; raised before any socket opens. */
sealed class QuicOptionRefusedException(
    message: String,
) : UnsupportedOperationException(message) {
    /** What was refused. */
    abstract val refused: QuicOptionRefusal
}

/** An option value refused for [refused]. */
class QuicOptionNotHonouredException(
    override val refused: QuicOptionRefusal,
) : QuicOptionRefusedException("${refused.option} cannot be honoured here: $refused")
