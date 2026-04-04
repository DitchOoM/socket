package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

// Type-safe wrappers for opaque quiche C pointers.
// Erased to Long at runtime (zero overhead) but prevent mixing
// incompatible handles at compile time — passing a QuicheConfig where
// a QuicheConn is expected is a compile error, not a segfault.

/** Opaque handle to a `quiche_config*`. */
@JvmInline
value class QuicheConfig(
    val ptr: Long,
) {
    init {
        require(ptr != 0L) { "QuicheConfig pointer must not be null" }
    }
}

/** Opaque handle to a `quiche_conn*`. */
@JvmInline
value class QuicheConn(
    val ptr: Long,
) {
    init {
        require(ptr != 0L) { "QuicheConn pointer must not be null" }
    }
}

/** Opaque handle to a `quiche_recv_info*` struct. */
@JvmInline
value class QuicheRecvInfo(
    val ptr: Long,
) {
    init {
        require(ptr != 0L) { "QuicheRecvInfo pointer must not be null" }
    }
}

/** Opaque handle to a `quiche_send_info*` struct. */
@JvmInline
value class QuicheSendInfo(
    val ptr: Long,
) {
    init {
        require(ptr != 0L) { "QuicheSendInfo pointer must not be null" }
    }
}
