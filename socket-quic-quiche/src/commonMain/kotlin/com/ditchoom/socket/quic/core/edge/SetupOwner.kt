package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo

/**
 * Who releases what a server's setup opened, at each step of it. A setup that fails releases through its owner at that
 * step, so the poller, the quiche config and the socket each have exactly one releaser, whatever step failed.
 */
internal sealed interface SetupOwner {
    /** The setup frees the poller and the quiche config. */
    data object Setup : SetupOwner

    /** The setup also closes the socket it opened. */
    class Opened(
        val socket: DatagramBatchIo,
    ) : SetupOwner

    /** The loop owns the poller and its memory hold the config; the setup still closes the socket. */
    class Built(
        val loop: Loop,
        val socket: DatagramBatchIo,
    ) : SetupOwner

    /** The loop owns the socket too, through its endpoint: its stop, on this thread, releases everything once. */
    class Placed(
        val loop: Loop,
    ) : SetupOwner

    /** The loop runs on a thread of its own: only its stop releases, once it is asked to. */
    class Started(
        val loop: Loop,
    ) : SetupOwner

    /** The server is built: its stop releases everything. */
    data object Served : SetupOwner
}
