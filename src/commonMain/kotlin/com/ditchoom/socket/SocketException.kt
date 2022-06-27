package com.ditchoom.socket

class SocketException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
