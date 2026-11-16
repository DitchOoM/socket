package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork

/** Puts a connection in the loop's next pass. One per connection, queued at most once at a time. */
internal class ServeConn(
    private val conn: Conn,
) : LoopWork() {
    override fun run(port: LoopPort) = conn.touchedByLoop()

    override fun gone(end: ConnEnd) = Unit
}

/** Inside: the connection's deadline fell due. One per connection. */
internal class TimerWork(
    private val conn: Conn,
) : ConnWork() {
    override fun run(inside: Inside) = conn.timerFiredInside()

    override fun gone(end: ConnEnd) = Unit
}

/** Inside, once: the loop or the socket ends the connection with [end]. */
internal class EndWork(
    private val end: ConnEnd,
) : ConnWork() {
    override fun run(inside: Inside) = inside.end(end)

    override fun gone(end: ConnEnd) = Unit
}
