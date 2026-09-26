package com.ditchoom.socket.udp

// This module's side of the shared io_uring engine (root src/linuxIoUringShared).

/**
 * Thrown when the io_uring ring cannot be set up (kernel < 5.1, or io_uring disabled). The datagram
 * substrate requires io_uring; there is no epoll fallback in this module.
 */
class IoUringUnavailableException(
    message: String,
) : RuntimeException(message)

/** What a caller sees when the kernel gives this module no io_uring ring. */
internal typealias IoUringFailure = IoUringUnavailableException

/** This module, as named in io_uring failures and diagnostics. */
internal const val IO_URING_MODULE = ":socket-udp"

/** The name of the worker thread that owns this module's ring. */
internal const val IO_URING_POLLER_THREAD = "io_uring-udp-poller"
