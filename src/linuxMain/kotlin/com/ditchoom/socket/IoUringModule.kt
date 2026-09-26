package com.ditchoom.socket

// This module's side of the shared io_uring engine (src/linuxIoUringShared).

/** What a caller sees when the kernel gives this module no io_uring ring. */
internal typealias IoUringFailure = SocketIOException

/** This module, as named in io_uring failures and diagnostics. */
internal const val IO_URING_MODULE = ":socket"

/** The name of the worker thread that owns this module's ring. */
internal const val IO_URING_POLLER_THREAD = "io_uring-poller"
