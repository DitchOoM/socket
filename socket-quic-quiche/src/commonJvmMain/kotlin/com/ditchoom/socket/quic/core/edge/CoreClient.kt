package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.loop.ClientLoop

/** A connected [connection] with the [conn] and the shared [loop] under it, for what measures them. */
internal class CoreClient(
    val connection: CoreConnection,
    val conn: Conn,
    val loop: ClientLoop,
)
