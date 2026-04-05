package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

// Type-safe wrappers for opaque quiche C pointers.
// Erased to Long at runtime (zero overhead) but prevent mixing
// incompatible handles at compile time — passing a QuicheConfig where
// a QuicheConn is expected is a compile error, not a segfault.

/** Opaque handle to a `quiche_config*`. */
@JvmInline
value class QuicheConfig(
    val handle: Long,
) {
    init {
        require(handle != 0L) { "QuicheConfig handle must not be null" }
    }
}

/** Opaque handle to a `quiche_conn*`. */
@JvmInline
value class QuicheConn(
    val handle: Long,
) {
    init {
        require(handle != 0L) { "QuicheConn handle must not be null" }
    }
}

/** Opaque handle to a `quiche_recv_info*` struct. */
@JvmInline
value class QuicheRecvInfo(
    val handle: Long,
) {
    init {
        require(handle != 0L) { "QuicheRecvInfo handle must not be null" }
    }
}

/** Opaque handle to a `quiche_send_info*` struct. */
@JvmInline
value class QuicheSendInfo(
    val handle: Long,
) {
    init {
        require(handle != 0L) { "QuicheSendInfo handle must not be null" }
    }
}

/** Opaque handle to a `quiche_stream_iter*`. 0 = no readable/writable streams. */
@JvmInline
value class QuicheStreamIter(
    val handle: Long,
) {
    val isExhausted: Boolean get() = handle == 0L
}
