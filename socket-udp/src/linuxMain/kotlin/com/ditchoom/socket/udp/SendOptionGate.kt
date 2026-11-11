package com.ditchoom.socket.udp

import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.AtomicReference

/**
 * Lets concurrent sends share a socket whose send options are socket-wide.
 *
 * `IP_TOS`, `IP_TTL` and the Don't-Fragment mode are set with `setsockopt` and read by the kernel when the
 * `sendmsg` runs, so two overlapping sends that want different values cannot both be right: whichever
 * `setsockopt` ran last decides what both datagrams carry. The gate admits sends that want the same
 * [Wire] options together and makes a send that wants different ones wait until those have drained. The
 * first send of each run puts its options in force before anyone else in the run proceeds.
 *
 * Lock-free: one [AtomicReference] holds the run (or [Idle]); entering and leaving are CAS loops, and a waiter
 * suspends on the run's `drained` latch, so no thread blocks. A QUIC server's sends all carry the same options,
 * so for it the gate is a counter.
 */
internal class SendOptionGate {
    /** The socket-wide options a send needs in force. -1 leaves TOS or TTL as the socket has them. */
    data class Wire(
        val tos: Int,
        val dontFragment: Boolean,
        val hopLimit: Int,
    )

    private sealed interface Run

    private data object Idle : Run

    private class Holding(
        val wire: Wire,
        val holders: Int,
        /** Completed once the run's first send has put [wire] in force. */
        val inForce: CompletableDeferred<Unit>,
        /** Completed when the last holder leaves. */
        val drained: CompletableDeferred<Unit>,
    ) : Run {
        fun with(holders: Int) = Holding(wire, holders, inForce, drained)
    }

    private val run = AtomicReference<Run>(Idle)

    /** Runs [block] with [wire] in force, calling [putInForce] first if this send starts the run. */
    suspend fun <T> hold(
        wire: Wire,
        putInForce: () -> Unit,
        block: suspend () -> T,
    ): T {
        while (true) {
            when (val current = run.value) {
                Idle -> {
                    val mine = Holding(wire, 1, CompletableDeferred(), CompletableDeferred())
                    if (run.compareAndSet(current, mine)) {
                        try {
                            putInForce()
                        } finally {
                            mine.inForce.complete(Unit)
                        }
                        return leaveAfter(mine, block)
                    }
                }
                is Holding ->
                    if (current.wire == wire) {
                        if (run.compareAndSet(current, current.with(current.holders + 1))) {
                            current.inForce.await()
                            return leaveAfter(current, block)
                        }
                    } else {
                        current.drained.await()
                    }
            }
        }
    }

    private suspend fun <T> leaveAfter(
        joined: Holding,
        block: suspend () -> T,
    ): T {
        try {
            return block()
        } finally {
            leave(joined)
        }
    }

    private fun leave(joined: Holding) {
        while (true) {
            val current = run.value as Holding
            if (current.holders > 1) {
                if (run.compareAndSet(current, current.with(current.holders - 1))) return
            } else if (run.compareAndSet(current, Idle)) {
                joined.drained.complete(Unit)
                return
            }
        }
    }
}
