package com.ditchoom.socket

class SocketException(message: String, wasInitiatedClientSize: Boolean = false, cause: Throwable? = null): Exception(message, cause)