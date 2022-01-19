package com.ditchoom.socket

class SocketException(override val message: String, val wasInitiatedClientSide: Boolean = false, override val cause: Throwable? = null): Exception(message, cause)