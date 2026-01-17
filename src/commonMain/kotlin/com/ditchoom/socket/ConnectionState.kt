package com.ditchoom.socket

sealed interface ConnectionState {
    data object Initialized : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState

    open class Disconnected(
        val t: Throwable? = null,
    ) : ConnectionState
}
