package com.ditchoom.socket

actual abstract class PlatformIOException actual constructor(
    message: String,
    cause: Throwable?,
) : java.io.IOException(message, cause)
