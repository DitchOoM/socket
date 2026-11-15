package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.loop.Collected
import com.ditchoom.socket.quic.core.loop.LoopParts
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch

private sealed interface Attached {
    object Nothing : Attached

    class One(
        val conn: Conn,
    ) : Attached
}

/** A loop whose socket is connected to one peer and carries one connection: a client's own socket. */
internal class OneConnParts : LoopParts {
    private var attached: Attached = Attached.Nothing

    /** Before the loop's first pass. The connection is built with the loop, so it is attached after both exist. */
    fun attach(conn: Conn) {
        attached = Attached.One(conn)
    }

    override fun onDatagram(
        buffer: ReceiveBuffer,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
    ) {
        when (val now = attached) {
            Attached.Nothing -> Unit
            is Attached.One -> now.conn.received(buffer, length)
        }
    }

    override fun collect(out: SendBatch): Collected =
        when (val now = attached) {
            Attached.Nothing -> Collected.Everything
            is Attached.One -> now.conn.collect(out)
        }
}
