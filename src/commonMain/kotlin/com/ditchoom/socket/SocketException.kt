package com.ditchoom.socket

open class SocketException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)


class SocketClosedException(
    override val message: String,
    override val cause: Throwable? = null
) : SocketException(message, cause)

class SocketUnknownHostException(
    hostname: String?,
    extraMessage: String = "",
    override val cause: Throwable? = null
) : SocketException(
    "Failed to get a socket address for hostname: $hostname${if (extraMessage.isNotEmpty()) "\r\nextraMessage" else ""}",
    cause
)

open class SSLSocketException(message: String, cause: Throwable? = null) :
    SocketException(message, cause)

class SSLHandshakeFailedException(source: Exception) :
    SSLSocketException(source.message ?: "Failed to complete SSL handshake", source)