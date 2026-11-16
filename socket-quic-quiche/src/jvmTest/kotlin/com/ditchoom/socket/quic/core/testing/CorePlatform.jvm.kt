package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.core.edge.coreTlsConfig
import com.ditchoom.socket.quic.loadQuicheApi

// The platform calls the shared core suites (src/coreTest) make, on the JVM and on Android.

internal fun platformQuicheApi(): QuicheApi = loadQuicheApi()

internal fun platformTlsConfig(): QuicTlsConfig = coreTlsConfig()

internal fun currentThreadName(): String = Thread.currentThread().name

/** Runs [block] on a new platform thread named [name] and returns once it ended. */
internal fun runOnANewThread(
    name: String,
    block: () -> Unit,
) {
    val thread = Thread(block, name)
    thread.start()
    thread.join(THREAD_ENDS_WITHIN_MILLIS)
    if (thread.isAlive) throw AssertionError("$name did not end within $THREAD_ENDS_WITHIN_MILLIS ms")
}

private const val THREAD_ENDS_WITHIN_MILLIS = 10_000L

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
