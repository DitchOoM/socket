@file:OptIn(ExperimentalAtomicApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The lent channels a QUIC connection or listener holds now, by identity. A channel carries one QUIC role at a time:
 * two readers of one channel would take each other's datagrams.
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

/** Who holds a claimed channel, and so who lets go of it: the connect or serve that claimed it, then its io. */
private enum class Holder { Entry, Io, Released }

/** Whether a lent channel's io took its claim from the connect or serve that made it. */
internal enum class ClaimTaken { ByIo, AlreadyReleased }

/**
 * One connect's or serve's claim of a channel. The entry holds it until the io starts and takes it; the entry lets go
 * only of a claim the io never took ([entryFailed]), the io of one it took or never started ([ioDone]). Each lets go
 * once, so the channel is released exactly once.
 */
@Published
internal class LentClaim(
    private val channel: AddressedDatagramChannel,
) {
    private val holder = AtomicReference(Holder.Entry)

    /** The io starts: it holds the claim from now on, unless the entry already let go of it. */
    fun takeByIo(): ClaimTaken = if (holder.compareAndSet(Holder.Entry, Holder.Io)) ClaimTaken.ByIo else ClaimTaken.AlreadyReleased

    /** The connect or serve failed: a claim no io took is released. */
    fun entryFailed() = letGo(Holder.Entry)

    /** The io is done with the channel, its coroutines ended; or it was closed before it started. */
    fun ioDone() {
        letGo(Holder.Io)
        letGo(Holder.Entry)
    }

    private fun letGo(from: Holder) {
        if (holder.compareAndSet(from, Holder.Released)) LentChannels.release(channel)
    }
}
