package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.toPathKey
import kotlin.test.assertNotSame
import kotlin.test.fail

/** What a test can see of a client socket on either stack: what it is bound to, and whether it was replaced. */
internal sealed interface SocketView {
    fun identity(): Any

    fun local(): Any

    fun assertReplaced(before: Any)

    class Jdk(
        private val io: NioOneByOne,
    ) : SocketView {
        override fun identity(): Any = io.channel

        override fun local(): Any = (io.channel.localAddress as java.net.InetSocketAddress).toPathKey()

        override fun assertReplaced(before: Any) = assertNotSame(before, io.channel, "a new socket carries the connection")
    }

    class Native(
        private val io: NativeIo,
    ) : SocketView {
        override fun identity(): Any = io.descriptor()

        override fun local(): Any = io.local()

        /** The kernel may hand the new socket the old one's number: the io's reopen count is the proof here. */
        override fun assertReplaced(before: Any) = Unit
    }

    companion object {
        fun of(io: DatagramBatchIo): SocketView =
            when (io) {
                is NioOneByOne -> Jdk(io)
                is NativeIo -> Native(io)
                else -> fail("no view of ${io::class}")
            }
    }
}
