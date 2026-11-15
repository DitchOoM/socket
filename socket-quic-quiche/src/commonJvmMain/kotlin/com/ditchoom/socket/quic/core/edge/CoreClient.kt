package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.loop.Loop

/** A connected [connection] with the [conn] and [loop] under it, for what measures them. */
internal class CoreClient(
    val connection: CoreConnection,
    val conn: Conn,
    val loop: Loop,
)
