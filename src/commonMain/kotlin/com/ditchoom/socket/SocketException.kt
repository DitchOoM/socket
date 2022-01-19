package com.ditchoom.socket

class SocketException(override val message: String, val wasInitiatedClientSize: Boolean = false, override val cause: Throwable? = null): Exception(message, cause)