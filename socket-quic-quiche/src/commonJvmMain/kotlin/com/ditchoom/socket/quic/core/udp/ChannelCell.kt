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
