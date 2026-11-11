package com.ditchoom.socket

import java.net.SocketException

/**
 * Answers a `NetworkInterface` flag query (`isUp`, `isLoopback`, ...), where an interface that
 * disappeared between being listed and being queried (the JDK throws [SocketException]) has none of the
 * flags set. Only that failure is absorbed; cancellation and programming errors propagate.
 */
internal inline fun flagOrGone(query: () -> Boolean): Boolean =
    try {
        query()
    } catch (_: SocketException) {
        false
    }
