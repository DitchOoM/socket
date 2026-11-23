package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume

/**
 * A caller waiting in [DatagramSend.NotSent.QueueFull.awaitRoom][com.ditchoom.socket.quic.DatagramSend.NotSent.QueueFull.awaitRoom],
 * parked inside the connection until its send queue is at its low-water mark or closed. Answered once.
 */
internal class RoomWait(
    private val caller: CancellableContinuation<Unit>,
) : ConnWork() {
    override fun run(inside: Inside) =
        when (val datagrams = inside.datagrams) {
            is Datagrams.Off -> room()
            is Datagrams.On -> datagrams.out.awaitRoom(this)
        }

    override fun gone(end: ConnEnd) = room()

    /** The caller goes on; one cancelled meanwhile is not resumed. */
    fun room() = caller.resume(Unit)
}

/** Takes back a [RoomWait] whose caller was cancelled. */
internal class RoomWithdraw(
    private val wait: RoomWait,
) : ConnWork() {
    override fun run(inside: Inside) =
        when (val datagrams = inside.datagrams) {
            is Datagrams.Off -> Unit
            is Datagrams.On -> datagrams.out.withdraw(wait)
        }

    override fun gone(end: ConnEnd) = Unit
}

/** Has the connection served after a send queued into an idle queue: its next write feeds quiche. One per queue, reused. */
internal class FeedWork(
    private val queue: DatagramQueue,
) : ConnWork() {
    override fun run(inside: Inside) = queue.fed()

    override fun gone(end: ConnEnd) = queue.fed()
}
