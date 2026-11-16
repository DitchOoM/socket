package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.SocketRefusal
import java.io.IOException
import java.net.SocketOption
import java.nio.channels.DatagramChannel

/**
 * Sets DF on this channel's datagrams (RFC 9000 §14) through the JDK's `IP_DONTFRAGMENT`, found by name because the
 * option is not in every JDK and Android level. Call it before the channel carries a datagram.
 */
internal fun DatagramChannel.forbidFragmentation(): DontFragment {
    val option = supportedOptions().firstOrNull { it.name() == "IP_DONTFRAGMENT" } ?: return DontFragment.NotOffered
    return try {
        enable(option)
        DontFragment.Set
    } catch (refused: IOException) {
        DontFragment.Refused(SocketRefusal.Unmapped)
    } catch (refused: UnsupportedOperationException) {
        DontFragment.NotOffered
    }
}

/** Sets a boolean [option] on; the option's own type says what "on" is. */
private fun <T> DatagramChannel.enable(option: SocketOption<T>) {
    setOption(option, option.type().cast(true))
}
