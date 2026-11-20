package com.ditchoom.socket.quic

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What `quiche_pin_monotonic_time_nanos` answered. */
internal sealed interface PinAnswer {
    data object Pinned : PinAnswer

    data object Unsupported : PinAnswer
}

/**
 * libquiche's clock pin, bound by each real backend: until [unpin], every clock read libquiche makes on the calling
 * thread returns the pinned instant. Where an `Instant` is not a `CLOCK_MONOTONIC` timespec (Apple, Windows) nothing
 * is pinned and the answer is [PinAnswer.Unsupported].
 */
internal interface QuicheClockPin {
    fun pinMonotonic(nanos: Long): PinAnswer

    fun unpin()
}

/** Whether an api object has a clock pin bound to it. */
internal sealed interface PinLookup {
    class Bound(
        val pin: QuicheClockPin,
    ) : PinLookup

    data object NotBound : PinLookup
}

/**
 * The clock pins of the loaded backends, by the api object each binds: a backend binds itself when it loads, and a
 * wrapper that forwards every call binds the pin of what it wraps. An api nobody bound, such as a simulation's
 * decorator that sets its own virtual time, has none.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object QuicheClockPins {
    private class Binding(
        val api: QuicheApi,
        val pin: QuicheClockPin,
    )

    private val bindings = AtomicReference<List<Binding>>(emptyList())

    fun bind(
        api: QuicheApi,
        pin: QuicheClockPin,
    ) {
        while (true) {
            val seen = bindings.load()
            if (seen.any { it.api === api }) return
            if (bindings.compareAndSet(seen, seen + Binding(api, pin))) return
        }
    }

    fun of(api: QuicheApi): PinLookup {
        for (binding in bindings.load()) {
            if (binding.api === api) return PinLookup.Bound(binding.pin)
        }
        return PinLookup.NotBound
    }
}
