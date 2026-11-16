package com.ditchoom.socket.quic.core.testing

/** Where a failed core test's flight recordings go: the `core.flight.dir` property, else this process's temp directory. */
internal fun flightDirectory(): String = System.getProperty("core.flight.dir") ?: (System.getProperty("java.io.tmpdir") + "/core-flight")

/** Creates [path] and its parents; false when it could not be created. */
internal fun makeDirectories(path: String): Boolean = java.io.File(path).let { it.isDirectory || it.mkdirs() }

/** Writes [text] to [path]; false when it could not be written. */
internal fun writeTextFile(
    path: String,
    text: String,
): Boolean =
    try {
        java.io.File(path).writeText(text)
        true
    } catch (failed: java.io.IOException) {
        false
    }
