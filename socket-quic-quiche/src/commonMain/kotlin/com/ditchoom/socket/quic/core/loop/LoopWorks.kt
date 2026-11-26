package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SocketTicket
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
    override fun run(port: LoopPort) = endpoint.rehome(loop.poller, loop)

    override fun gone(end: ConnEnd) = Unit
}

/** Any thread: [endpoint] opens a second socket on [bind]'s address, a new local endpoint (see [Endpoint.move]). */
internal fun Loop.move(
    endpoint: Endpoint,
    bind: PathKey,
    prior: RttPrior,
) = port.submit(MoveWork(this, endpoint, bind, prior))

/** Any thread: [endpoint]'s second socket [ticket] names carries from now on (see [Endpoint.promote]). */
internal fun Loop.promote(
    endpoint: Endpoint,
    ticket: SocketTicket,
) = port.submit(PromoteWork(this, endpoint, ticket))

/** Any thread: [endpoint]'s second socket [ticket] names is closed (see [SecondSocket.close]). */
internal fun Loop.closeSecond(
    endpoint: Endpoint,
    ticket: SocketTicket,
) = port.submit(CloseSecondWork(this, endpoint, ticket))

/** Loop work: a second socket is opened beside the carrying one, on a new local address. */
internal class MoveWork(
    private val loop: Loop,
    private val endpoint: Endpoint,
    private val bind: PathKey,
    private val prior: RttPrior,
) : LoopWork() {
    override fun run(port: LoopPort) = endpoint.move(loop.poller, loop, bind, prior)

    override fun gone(end: ConnEnd) = Unit
}

/** Loop work: the second socket [ticket] names carries from now on; a stale ticket is a no-op. */
internal class PromoteWork(
    private val loop: Loop,
    private val endpoint: Endpoint,
    private val ticket: SocketTicket,
) : LoopWork() {
    override fun run(port: LoopPort) = endpoint.promote(loop.poller, loop, ticket)

    override fun gone(end: ConnEnd) = Unit
}

/** Loop work: the second socket [ticket] names is closed; a stale ticket is a no-op. */
internal class CloseSecondWork(
    private val loop: Loop,
    private val endpoint: Endpoint,
    private val ticket: SocketTicket,
) : LoopWork() {
    override fun run(port: LoopPort) = endpoint.second.close(loop.poller, loop, ticket)

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

/** How a loop's stop went. Every step runs once whatever an earlier one threw; the first failure is kept. */
internal sealed interface StopOutcome {
    data object Clean : StopOutcome

    class Failed(
        val first: Throwable,
    ) : StopOutcome

    /** It had stopped already: nothing was released. */
    data object AlreadyStopped : StopOutcome
}
