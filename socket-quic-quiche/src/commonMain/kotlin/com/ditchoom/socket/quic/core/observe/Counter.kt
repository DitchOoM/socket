@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.observe

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A count any thread may add to and any thread may read. */
@Published
internal class Counter {
    private val total = AtomicLong(0L)

    fun add(count: Int) {
        total.fetchAndAdd(count.toLong())
    }

    fun increment() {
        total.fetchAndAdd(1L)
    }

    fun value(): Long = total.load()
}
