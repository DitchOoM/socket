package com.ditchoom.socket

/**
 * Platform-specific base class for socket exceptions.
 *
 * On JVM/Android: extends [java.io.IOException] so that `catch (e: IOException)` catches socket errors
 * (matching Java's convention where `java.net.SocketException` extends `IOException`).
 *
 * On JS/Native: extends [Exception] (no platform `IOException` to extend).
 */
expect abstract class PlatformIOException(
    message: String,
    cause: Throwable?,
) : Exception
