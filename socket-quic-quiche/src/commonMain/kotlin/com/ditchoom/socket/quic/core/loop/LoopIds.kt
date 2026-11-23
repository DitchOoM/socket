@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/** Numbers loops, so each loop's turns are its own in a connection's owner word. */
@Published
internal object LoopIds {
    private val sequence = AtomicInt(1)

    fun next(): Int = sequence.fetchAndIncrement()
}
