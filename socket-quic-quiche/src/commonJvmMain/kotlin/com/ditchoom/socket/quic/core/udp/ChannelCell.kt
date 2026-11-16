@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.core.observe.Published
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The channel in use, or the one being replaced while its successor is opened. */
private sealed interface Held {
    class Live(
        val channel: DatagramChannel,
    ) : Held

    class Replacing(
        val channel: DatagramChannel,
    ) : Held
}

private fun Held.channel(): DatagramChannel =
    when (this) {
        is Held.Live -> channel
        is Held.Replacing -> channel
    }

/**
 * The channel a socket's datagrams go through now. The loop replaces it when the network under it changed
 * ([reopen]); any thread sending reads it per send, and a send that met the old channel closed can tell
 * ([replaced]), because the cell no longer holds it as live.
 */
@Published
internal class ChannelCell(
    first: DatagramChannel,
) {
    private val current = AtomicReference<Held>(Held.Live(first))

    fun load(): DatagramChannel = current.load().channel()

    /**
     * Loop thread, with the channel out of its selector: closes the channel and opens one in its place, bound to the
     * same local address and port and connected to the same peer, with the same buffer sizes. A fresh socket is what
     * a changed network needs: Android tags a socket with the network it was connected on, and a socket of a network
     * that is gone carries nothing even when the new network hands back the same address.
     */
    fun reopen(): Reopened {
        val old = current.load().channel()
        current.store(Held.Replacing(old))
        val fresh =
            try {
                DatagramChannel.open()
            } catch (refused: IOException) {
                return refuse(old, refused)
            }
        return try {
            val local = old.localAddress
            val peer = old.remoteAddress
            val receive = old.getOption(StandardSocketOptions.SO_RCVBUF)
            val send = old.getOption(StandardSocketOptions.SO_SNDBUF)
            old.close()
            fresh.forbidFragmentation()
            fresh.setOption(StandardSocketOptions.SO_RCVBUF, receive)
            fresh.setOption(StandardSocketOptions.SO_SNDBUF, send)
            fresh.bind(local)
            if (peer is InetSocketAddress) fresh.connect(peer)
            fresh.configureBlocking(false)
            current.store(Held.Live(fresh))
            Reopened.SameEndpoint
        } catch (refused: IOException) {
            fresh.close()
            refuse(old, refused)
        }
    }

    /**
     * Loop thread, with the channel out of its selector: opens a channel bound to [bind] on a port of its own,
     * connected to the same peer, with the same buffer sizes, and closes the old one once the new one is open. A new
     * channel that cannot be opened there leaves the old one in place.
     */
    fun moveTo(bind: InetSocketAddress): Moved {
        val old = current.load().channel()
        current.store(Held.Replacing(old))
        val fresh =
            try {
                DatagramChannel.open()
            } catch (refused: IOException) {
                current.store(Held.Live(old))
                return Moved.Refused(Thrown(refused))
            }
        return try {
            fresh.setOption(StandardSocketOptions.SO_RCVBUF, old.getOption(StandardSocketOptions.SO_RCVBUF))
            fresh.setOption(StandardSocketOptions.SO_SNDBUF, old.getOption(StandardSocketOptions.SO_SNDBUF))
            fresh.bind(bind)
            val peer = old.remoteAddress
            if (peer is InetSocketAddress) fresh.connect(peer)
            fresh.configureBlocking(false)
            val local = fresh.localAddress
            if (local is InetSocketAddress) {
                old.close()
                current.store(Held.Live(fresh))
                Moved.To(local.toPathKey())
            } else {
                // A channel bound to an internet address reports one; one that does not cannot carry the path.
                fresh.close()
                current.store(Held.Live(old))
                Moved.Unsupported
            }
        } catch (refused: IOException) {
            fresh.close()
            current.store(Held.Live(old))
            Moved.Refused(Thrown(refused))
        }
    }

    private fun refuse(
        old: DatagramChannel,
        refused: IOException,
    ): Reopened {
        old.close()
        current.store(Held.Live(old))
        return Reopened.Refused(Thrown(refused))
    }

    /** Whether [used] is being or was replaced since a sender read it: its failure is the swap's, not the socket's. */
    fun replaced(used: DatagramChannel): Boolean =
        when (val held = current.load()) {
            is Held.Live -> held.channel !== used
            is Held.Replacing -> true
        }
}
