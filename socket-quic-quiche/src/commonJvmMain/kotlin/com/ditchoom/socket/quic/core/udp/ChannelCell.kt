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
internal sealed interface Held {
    class Live(
        val channel: DatagramChannel,
    ) : Held

    class Replacing(
        val channel: DatagramChannel,
    ) : Held
}

internal fun Held.channel(): DatagramChannel =
    when (this) {
        is Held.Live -> channel
        is Held.Replacing -> channel
    }

/** The channel opened beside the carrying one while a move is open, or none. */
internal sealed interface SecondChannel {
    data object None : SecondChannel

    class Open(
        val channel: DatagramChannel,
        val ticket: SocketTicket,
    ) : SecondChannel
}

/** A socket's channels as one reading: the carrying one and its ticket, and the second one beside it. */
internal class Channels(
    val held: Held,
    val ticket: SocketTicket,
    val second: SecondChannel,
)

/**
 * The channel a socket's datagrams go through now, and the second one beside it while a client moves. The loop replaces
 * the carrying one when the network under it changed ([reopen]), opens a second ([openBeside]) and promotes or closes
 * it; any thread sending reads them per send, and a send that met the old channel closed can tell ([replaced]),
 * because the cell no longer holds it as live.
 */
@Published
internal class ChannelCell(
    first: DatagramChannel,
) {
    private val current = AtomicReference(Channels(Held.Live(first), SocketTicket.FIRST, SecondChannel.None))

    fun load(): DatagramChannel = current.load().held.channel()

    /** The channels as one reading, for a send. */
    fun channels(): Channels = current.load()

    /** The second channel, while one is open. */
    fun second(): SecondChannel = current.load().second

    /**
     * Loop thread, with the channel out of its selector: closes the channel and opens one in its place, bound to the
     * same local address and port and connected to the same peer, with the same buffer sizes. A fresh socket is what
     * a changed network needs: Android tags a socket with the network it was connected on, and a socket of a network
     * that is gone carries nothing even when the new network hands back the same address.
     */
    fun reopen(): Reopened {
        val was = current.load()
        val old = was.held.channel()
        current.store(Channels(Held.Replacing(old), was.ticket, was.second))
        return when (val fresh = reopened(old)) {
            is Opened.Channel -> {
                current.store(Channels(Held.Live(fresh.channel), was.ticket, was.second))
                Reopened.SameEndpoint
            }
            is Opened.Refused -> {
                current.store(Channels(Held.Live(old), was.ticket, was.second))
                Reopened.Refused(fresh.thrown)
            }
        }
    }

    /** Loop thread, with the second channel out of the selector: [reopen] for the second channel. */
    fun reopenSecond(): Reopened {
        val was = current.load()
        val second =
            when (val now = was.second) {
                is SecondChannel.Open -> now
                is SecondChannel.None -> return Reopened.SameEndpoint
            }
        current.store(Channels(was.held, was.ticket, SecondChannel.None))
        return when (val fresh = reopened(second.channel)) {
            is Opened.Channel -> {
                current.store(Channels(was.held, was.ticket, SecondChannel.Open(fresh.channel, second.ticket)))
                Reopened.SameEndpoint
            }
            is Opened.Refused -> Reopened.Refused(fresh.thrown)
        }
    }

    /**
     * Loop thread: opens a channel bound to [bind] on a port of its own, connected to the same peer, with the same buffer
     * sizes, beside the carrying one, and names it [ticket]; a second one still open is closed first. The carrying channel
     * is untouched.
     */
    fun openBeside(
        bind: InetSocketAddress,
        ticket: SocketTicket,
    ): Beside {
        closeSecond()
        val was = current.load()
        val old = was.held.channel()
        val fresh =
            try {
                DatagramChannel.open()
            } catch (refused: IOException) {
                return Beside.Refused(Thrown(refused))
            }
        return try {
            fresh.forbidFragmentation()
            fresh.setOption(StandardSocketOptions.SO_RCVBUF, old.getOption(StandardSocketOptions.SO_RCVBUF))
            fresh.setOption(StandardSocketOptions.SO_SNDBUF, old.getOption(StandardSocketOptions.SO_SNDBUF))
            fresh.bind(bind)
            val peer = old.remoteAddress
            if (peer is InetSocketAddress) fresh.connect(peer)
            fresh.configureBlocking(false)
            val local = fresh.localAddress
            if (local is InetSocketAddress) {
                current.store(Channels(was.held, was.ticket, SecondChannel.Open(fresh, ticket)))
                Beside.Opened(local.toPathKey())
            } else {
                // A channel bound to an internet address reports one; one that does not cannot carry the path.
                fresh.close()
                Beside.Unsupported
            }
        } catch (refused: IOException) {
            fresh.close()
            Beside.Refused(Thrown(refused))
        }
    }

    /** Loop thread, with both channels out of the selector: the second channel carries, and the old one is closed. */
    fun promote() {
        val was = current.load()
        val second =
            when (val now = was.second) {
                is SecondChannel.Open -> now
                is SecondChannel.None -> return
            }
        val old = was.held.channel()
        current.store(Channels(Held.Replacing(old), was.ticket, was.second))
        old.close()
        current.store(Channels(Held.Live(second.channel), second.ticket, SecondChannel.None))
    }

    /** Loop thread, with the second channel out of the selector: closes it. */
    fun closeSecond() {
        val was = current.load()
        when (val second = was.second) {
            is SecondChannel.Open -> {
                current.store(Channels(was.held, was.ticket, SecondChannel.None))
                second.channel.close()
            }
            is SecondChannel.None -> Unit
        }
    }

    /** The second channel, for the selector, once one is open; the carrying one otherwise. */
    fun secondChannel(): DatagramChannel =
        when (val now = current.load().second) {
            is SecondChannel.Open -> now.channel
            is SecondChannel.None -> load()
        }

    /** Whether [used] is being or was replaced since a sender read it: its failure is the swap's, not the socket's. */
    fun replaced(used: DatagramChannel): Boolean =
        when (val held = current.load().held) {
            is Held.Live -> held.channel !== used
            is Held.Replacing -> true
        }
}

/** What opening a channel in an old one's place made. */
private sealed interface Opened {
    class Channel(
        val channel: DatagramChannel,
    ) : Opened

    class Refused(
        val thrown: Thrown,
    ) : Opened
}

/**
 * A channel bound to [old]'s local address and port and connected to its peer, with its buffer sizes. [old] is closed
 * first, so its port is free, whether or not the new one opens.
 */
private fun reopened(old: DatagramChannel): Opened {
    val fresh =
        try {
            DatagramChannel.open()
        } catch (refused: IOException) {
            old.close()
            return Opened.Refused(Thrown(refused))
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
        Opened.Channel(fresh)
    } catch (refused: IOException) {
        fresh.close()
        old.close()
        Opened.Refused(Thrown(refused))
    }
}
