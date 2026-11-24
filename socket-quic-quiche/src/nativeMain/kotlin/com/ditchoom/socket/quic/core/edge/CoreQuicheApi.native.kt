package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.CinteropQuicheApi
import com.ditchoom.socket.quic.QuicheApi

/** The cinterop binding, linked into the binary. */
internal actual val coreQuicheApi: QuicheApi get() = CinteropQuicheApi
