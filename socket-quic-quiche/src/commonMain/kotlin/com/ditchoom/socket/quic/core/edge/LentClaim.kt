@file:OptIn(ExperimentalAtomicApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The lent channels a QUIC connection or listener holds now, by object identity: a wrapper and the channel it wraps are
 * two channels to this, so lend the same object every time. A channel carries one QUIC role at a time: two readers of
 * one channel would take each other's datagrams.
 */
@Published
internal object LentChannels {
    private val held = AtomicReference<List<AddressedDatagramChannel>>(emptyList())

    /** [channel] for one connect or serve, or [Claimed.InUse] while another holds it. */
    fun claim(channel: AddressedDatagramChannel): Claimed {
        while (true) {
            val seen = held.load()
            if (seen.any { it === channel }) return Claimed.InUse
            if (held.compareAndSet(seen, seen + channel)) return Claimed.Lent(LentChannel(channel, LentClaim(channel)))
        }
    }

    /** Lets go of [channel]: the next connect or serve may claim it. */
    fun release(channel: AddressedDatagramChannel) {
        while (true) {
            val seen = held.load()
            if (held.compareAndSet(seen, seen.filterNot { it === channel })) return
        }
    }
}

/** What a claim of a lent channel answered. */
internal sealed interface Claimed {
    class Lent(
        val channel: LentChannel,
    ) : Claimed

    /** Another connection or listener holds the channel. */
    data object InUse : Claimed
}

/** Who holds a claimed channel, and so who lets go of it. */
private sealed interface Holder {
    /** The connect or serve that claimed it; no io exists yet. */
    data object Entry : Holder

    /** The entry still; its io made [slots] on the setup lane and has not started. */
    class Unstarted(
        val slots: List<NativeBytes>,
    ) : Holder

    /** The io, started, whose pump owns the slots. */
    data object Io : Holder

    data object Released : Holder
}

/** What starting an io found: the slots it made, now its pump's, or a claim its entry let go of meanwhile. */
internal sealed interface Start {
    class Taken(
        val slots: List<NativeBytes>,
    ) : Start

    /** The entry failed before the io started: the slots are freed and the channel is free. */
    data object EntryLetGo : Start
}

/**
 * One connect's or serve's claim of a channel, which ends with the channel free and nothing of the io's left: the
 * entry holds it, then the io's slots wait in it until the io starts, then the io holds it until its pump has ended.
 * Whoever lets go frees what it holds; each lets go once, so the channel is released exactly once.
 */
@Published
internal class LentClaim(
    private val channel: AddressedDatagramChannel,
) {
    private val holder = AtomicReference<Holder>(Holder.Entry)
    private val released = CompletableDeferred<Unit>()

    /** Setup lane: the io made [slots]; an entry that already failed takes nothing, and they are freed at once. */
    fun slotsMade(slots: List<NativeBytes>) {
        if (!holder.compareAndSet(Holder.Entry, Holder.Unstarted(slots))) for (slot in slots) slot.free()
    }

    /** Loop thread, once: the io starts and holds the claim from now on, with its slots. */
    fun start(): Start =
        when (val seen = holder.load()) {
            is Holder.Unstarted -> if (holder.compareAndSet(seen, Holder.Io)) Start.Taken(seen.slots) else Start.EntryLetGo
            is Holder.Entry, is Holder.Io, is Holder.Released -> Start.EntryLetGo
        }

    /** The connect or serve failed: a claim no io started on is released, with any slots its io made. */
    fun entryFailed() = letGoUnstarted()

    /** The io was closed before it started: as [entryFailed]. */
    fun closedUnstarted() = letGoUnstarted()

    /** The started io's pump has ended: nothing reads or sends on the channel any more. */
    fun pumpEnded() {
        if (holder.compareAndSet(Holder.Io, Holder.Released)) release()
    }

    /** Suspends until the channel is free again; a claim that was never held by an io is free once its entry let go. */
    suspend fun awaitReleased() = released.await()

    private fun letGoUnstarted() {
        while (true) {
            when (val seen = holder.load()) {
                is Holder.Entry -> if (holder.compareAndSet(seen, Holder.Released)) return release()
                is Holder.Unstarted ->
                    if (holder.compareAndSet(seen, Holder.Released)) {
                        for (slot in seen.slots) slot.free()
                        return release()
                    }
                is Holder.Io, is Holder.Released -> return
            }
        }
    }

    private fun release() {
        LentChannels.release(channel)
        released.complete(Unit)
    }
}
