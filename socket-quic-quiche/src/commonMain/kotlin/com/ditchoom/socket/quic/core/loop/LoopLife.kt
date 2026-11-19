@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.observe.Published
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
