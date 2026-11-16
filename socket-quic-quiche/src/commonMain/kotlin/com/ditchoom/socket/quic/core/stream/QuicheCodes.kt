package com.ditchoom.socket.quic.core.stream

// quiche's C result codes: the only place in core where a negative number means something.

internal const val QUICHE_ERR_DONE = -1
internal const val QUICHE_ERR_INVALID_STREAM_STATE = -7
internal const val QUICHE_ERR_STREAM_LIMIT = -12
internal const val QUICHE_ERR_STREAM_STOPPED = -15
internal const val QUICHE_ERR_STREAM_RESET = -16
