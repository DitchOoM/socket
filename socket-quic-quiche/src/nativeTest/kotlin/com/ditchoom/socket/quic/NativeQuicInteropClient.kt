@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import platform.posix.F_OK
import platform.posix.S_IRWXU
import platform.posix.access
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fprintf
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.stderr
import platform.posix.stdout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The interop client ([runInteropClient]) as a Kotlin/Native test binary, for the native interop lane
 * (test-harness/quic-interop/native-pair.py). Inert unless `QUIC_INTEROP_CLIENT=1`; then it reads the
 * endpoint contract (`TESTCASE`, `REQUESTS`, `DOWNLOADS`, `CLIENT_PARAMS`) from the environment, and the
 * binary exits non-zero when the client does. On a simulator every variable arrives as `SIMCTL_CHILD_<NAME>`
 * (test-harness/quic-interop/kn-client.sh adds the prefix).
 */
abstract class NativeQuicInteropClient {
    @Test
    fun runsTheInteropClientWhenAsked() {
        if (getenv("QUIC_INTEROP_CLIENT")?.toKString() != "1") return
        val code = runBlocking(Dispatchers.Default) { runInteropClient(PosixInteropHost) }
        fflush(stdout)
        assertEquals(0, code, "the interop client's exit code")
    }
}

private object PosixInteropHost : InteropHost {
    override fun env(name: String): String = getenv(name)?.toKString().orEmpty()

    override fun create(path: String): InteropDownload {
        makeParents(path)
        val file = fopen(path, "wb") ?: throw IllegalStateException("cannot create $path")
        return object : InteropDownload {
            override fun write(bytes: ByteArray) {
                if (bytes.isEmpty()) return
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file) }
                if (written.toInt() != bytes.size) throw IllegalStateException("short write to $path")
            }

            override fun close() {
                fclose(file)
            }
        }
    }

    override fun reportError(line: String) {
        fprintf(stderr, "%s\n", line)
    }

    override fun flushOutput() {
        fflush(stdout)
    }

    private fun makeParents(path: String) {
        val parts = path.split('/').dropLast(1)
        var dir = ""
        for (part in parts) {
            dir = if (dir.isEmpty() && path.startsWith('/')) "/$part" else "$dir/$part"
            if (part.isEmpty() || access(dir, F_OK) == 0) continue
            mkdir(dir, S_IRWXU.convert())
        }
    }
}
