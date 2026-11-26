package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.JcaPeerCertificateBackend
import com.ditchoom.socket.quic.PeerCertificateSupport
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.core.edge.coreTlsConfig
import com.ditchoom.socket.quic.loadQuicheApi

// The platform calls the shared core suites (src/coreTest) make, on the JVM and on Android.

internal fun platformQuicheApi(): QuicheApi = loadQuicheApi()

internal fun platformTlsConfig(): QuicTlsConfig = coreTlsConfig()

/** The certificates this platform can mint. */
internal fun platformPeerCertificates(): PeerCertificateSupport = PeerCertificateSupport.Available(JcaPeerCertificateBackend)

internal fun currentThreadName(): String = Thread.currentThread().name

/** A new, empty directory, as an absolute path. */
internal fun scratchDirectory(tag: String): String =
    java.nio.file.Files
        .createTempDirectory(tag)
        .toFile()
        .absolutePath

/** Every regular file directly in [dir], by name, with its text. */
internal fun filesByName(dir: String): Map<String, String> =
    java.io.File(dir).listFiles { f -> f.isFile }.orEmpty().associate { it.name to it.readText() }

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
