@file:OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)

package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.CinteropQuicheApi
import com.ditchoom.socket.quic.PeerCertificateSupport
import com.ditchoom.socket.quic.NativeTestFiles
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.mkdir
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

// The platform calls the shared core suites (src/coreTest) make, on Kotlin/Native.

internal fun platformQuicheApi(): QuicheApi = CinteropQuicheApi

internal fun platformTlsConfig(): QuicTlsConfig = nativeCoreTls()

/** The certificates this platform can mint: Apple and Linux have different backends. */
internal expect fun platformPeerCertificates(): PeerCertificateSupport

/** The server certificate the shared core suites serve: Apple and Linux find their test files differently. */
internal expect fun nativeCoreTls(): QuicTlsConfig

internal fun currentThreadName(): String = Worker.current.name

/** A new, empty directory, as an absolute path. */
internal fun scratchDirectory(tag: String): String = NativeTestFiles.newDirectory(tag)

/** Every regular file directly in [dir], by name, with its text. */
internal fun filesByName(dir: String): Map<String, String> = NativeTestFiles.filesIn(dir)

/** Runs [block] on a new worker named [name] and returns once it ended. */
internal fun runOnANewThread(
    name: String,
    block: () -> Unit,
) {
    val worker = Worker.start(name = name)
    worker.execute(TransferMode.SAFE, { block }) { it() }.result
    worker.requestTermination().result
}

/** Where a failed core test's flight recordings go: `CORE_FLIGHT_DIR`, else `build/core-flight` under the working directory. */
internal fun flightDirectory(): String =
    getenv("CORE_FLIGHT_DIR")?.toKString()
        ?: ((getcwd(null, 0u)?.toKString() ?: ".") + "/build/core-flight")

/** Creates [path] and its parents; false when it could not be created. */
internal fun makeDirectories(path: String): Boolean {
    var prefix = if (path.startsWith("/")) "" else "."
    for (part in path.split('/').filter { it.isNotEmpty() }) {
        prefix = "$prefix/$part"
        if (mkdir(prefix, 0b111_111_101u) != 0 && errno != EEXIST) return false
    }
    return true
}

/** Writes [text] to [path]; false when it could not be written. */
internal fun writeTextFile(
    path: String,
    text: String,
): Boolean {
    val file = fopen(path, "w") ?: return false
    val ok = fputs(text, file) >= 0
    return fclose(file) == 0 && ok
}
