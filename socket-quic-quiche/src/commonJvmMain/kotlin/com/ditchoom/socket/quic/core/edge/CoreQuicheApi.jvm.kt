package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.loadQuicheApi

/** The JNI binding; the first read extracts and loads the native library. */
internal actual val coreQuicheApi: QuicheApi get() = loadQuicheApi()
