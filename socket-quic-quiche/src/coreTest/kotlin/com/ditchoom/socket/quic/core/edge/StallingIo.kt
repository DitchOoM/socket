@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Whether a loop is let through to the network, or held inside its next send until released. */
internal sealed interface Stall {
    data object Off : Stall

    data object Armed : Stall

    data object Holding : Stall

    data object Released : Stall
}

/** Holds the loop that sends through it, once [stall] is armed, until it is released: a loop that gets no CPU. */
internal class StallingIo(
    private val real: DatagramBatchIo,
    private val stall: AtomicReference<Stall>,
) : DatagramBatchIo by real {
    override fun send(batch: SendBatch): SendBatchResult {
        if (stall.compareAndSet(Stall.Armed, Stall.Holding)) {
            while (stall.load() == Stall.Holding) Unit
        }
        return real.send(batch)
    }
}

/** Waits up to [within] for an armed [StallingIo] to hold its loop. */
internal suspend fun AtomicReference<Stall>.awaitHolding(within: Duration) =
    withTimeout(within) { while (load() != Stall.Holding) delay(1.milliseconds) }
