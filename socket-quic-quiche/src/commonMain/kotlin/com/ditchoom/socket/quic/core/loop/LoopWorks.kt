package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import kotlinx.coroutines.Runnable

/** Wakes the loop when a socket died on another thread: the next pass sees the socket's health. One per socket. */
internal class SocketDiedNotice : LoopWork() {
    override fun run(port: LoopPort) = Unit

    override fun gone(end: ConnEnd) = Unit
}

/** Loop work: a new connection's socket joins the loop. */
internal class PlaceWork(
    private val loop: Loop,
    private val endpoint: Endpoint,
) : LoopWork() {
    override fun run(port: LoopPort) = loop.attach(endpoint)

    override fun gone(end: ConnEnd) = endpoint.unplaced(end)
}

/** Loop work: a socket is reopened on a changed network. */
internal class RehomeWork(
    private val loop: Loop,
    private val endpoint: Endpoint,
) : LoopWork() {
    override fun run(port: LoopPort) = loop.reopen(endpoint)

    override fun gone(end: ConnEnd) = Unit
}

/** Loop work: a socket is moved to a new local address. */
internal class MoveWork(
    private val loop: Loop,
    private val endpoint: Endpoint,
    private val bind: PathKey,
) : LoopWork() {
    override fun run(port: LoopPort) = loop.moveTo(endpoint, bind)

    override fun gone(end: ConnEnd) = Unit
}

/** Loop work: the loop's owner stops it. */
internal class StopWork(
    private val loop: Loop,
    private val then: Runnable,
) : LoopWork() {
    override fun run(port: LoopPort) = loop.beginClosing(then)

    override fun gone(end: ConnEnd) = then.run()
}
