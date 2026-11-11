package com.ditchoom.socket.mainthread

import com.ditchoom.socket.useAsyncChannels
import com.ditchoom.socket.useNioBlocking

/**
 * The three JVM TCP client implementations `ClientSocket.allocate` chooses between. Each scenario runs
 * once per implementation, as its own test class, so each gets the fresh JVM a first use needs.
 */
enum class JvmClientImplementation(
    private val async: Boolean,
    private val blocking: Boolean,
) {
    AsyncChannel(async = true, blocking = false),
    NioSelector(async = false, blocking = false),
    NioBlocking(async = false, blocking = true),
    ;

    fun <T> selected(block: () -> T): T {
        useAsyncChannels = async
        useNioBlocking = blocking
        return try {
            block()
        } finally {
            useAsyncChannels = true
            useNioBlocking = false
        }
    }
}
