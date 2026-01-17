package com.ditchoom.socket

data class SocketDataRead<T>(
    val result: T,
    val bytesRead: Int,
)
