package com.ditchoom.socket

data class SocketOptions(
    val tcpNoDelay: Boolean? = null,
    val reuseAddress: Boolean? = null,
    val keepAlive: Boolean? = null,
    val receiveBuffer: Int? = null,
    val sendBuffer: Int? = null
)