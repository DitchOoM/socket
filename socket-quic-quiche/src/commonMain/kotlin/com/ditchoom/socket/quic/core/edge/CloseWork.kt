package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.ConnEnd

/** The close point of a close with [error], inside the connection. */
internal class CloseWork(
    private val error: QuicError,
) : ConnWork() {
    override fun run(inside: Inside) = inside.close(error)

    override fun gone(end: ConnEnd) = Unit
}

/** The close point of the close [choice] asks for, inside the connection. */
internal class ChosenCloseWork(
    private val choice: CloseChoice,
) : ConnWork() {
    override fun run(inside: Inside) = inside.close(choice)

    override fun gone(end: ConnEnd) = Unit
}
