package com.ditchoom.socket

actual abstract class PlatformIOException actual constructor(
    message: String,
    cause: Throwable?,
) : Exception(message, cause)
