@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.observe.Published
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whether a loop runs, as any thread may read it. */
internal enum class LoopLife { Alive, Stopped }

/** A loop's life, written once by the loop when it stops. */
@Published
internal class LoopLifeCell {
    private val state = AtomicReference(LoopLife.Alive)

    fun load(): LoopLife = state.load()

    fun stopped() = state.store(LoopLife.Stopped)
}

/** Where a loop's own thread stands: running, closing at the end of this pass, or stopped. */
internal sealed interface Run {
    data object Running : Run

    class Closing(
        val then: Runnable,
    ) : Run

    /** The stop began: what it releases is never released again, and the loop never runs another pass. */
    data object Stopped : Run
}
