package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn

/** A connection's quiche handle and the api that serves it. Which file may call what is rule Q's; the pair grants nothing. */
internal class QuicheConnRef(
    val api: QuicheApi,
    val handle: QuicheConn,
)
