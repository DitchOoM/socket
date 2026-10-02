package com.ditchoom.socket

import kotlin.concurrent.AtomicInt

/**
 * A socket's descriptor and its place in [IoUringManager]'s count of open sockets, which releases the ring
 * when it reaches zero. [adopt] counts the socket in the same step its descriptor becomes the socket's, so
 * a socket that fails after that point (a TLS handshake after the connect) uncounts exactly what it counted.
 * [release] hands the descriptor to exactly one caller however many closers race — a read that saw the
 * peer hang up and the owner's close — so it is closed once and uncounted once: a second `close(2)` could
 * close a descriptor number the kernel had already given to another socket.
 */
internal class SocketDescriptor {
    private val fd = AtomicInt(NO_DESCRIPTOR)

    /** The socket's descriptor while it holds one, negative before [adopt] and after [release]. */
    val value: Int get() = fd.value

    val isOpen: Boolean get() = fd.value >= 0

    fun adopt(descriptor: Int) {
        check(descriptor >= 0) { "not a descriptor: $descriptor" }
        check(fd.compareAndSet(NO_DESCRIPTOR, descriptor)) { "the socket already holds descriptor ${fd.value}" }
        IoUringManager.onSocketOpened()
    }

    /**
     * Gives up the descriptor if this caller is the one that takes it: runs [teardown] (what must happen
     * while the descriptor is still open, such as a TLS close_notify), closes it and uncounts the socket.
     * Every other caller returns without doing anything.
     */
    fun release(teardown: () -> Unit = {}) {
        val descriptor = fd.getAndSet(NO_DESCRIPTOR)
        if (descriptor < 0) return
        try {
            teardown()
        } finally {
            closeSocket(descriptor)
            IoUringManager.onSocketClosed()
        }
    }
}

private const val NO_DESCRIPTOR = -1
