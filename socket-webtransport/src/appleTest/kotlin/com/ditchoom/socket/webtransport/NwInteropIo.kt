@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.webtransport

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.remove

/**
 * Tiny posix file/env helpers shared by the Kotlin/Native cross-impl interop runners ([NwInteropServer],
 * [NwInteropClient]). K/N test binaries have no JVM `File`/`System.getProperty`; runtime config is passed
 * via environment variables and a small `config.properties` file the server writes and the client reads.
 */
internal fun env(name: String): String? = getenv(name)?.toKString()

internal fun fileExists(path: String): Boolean = access(path, F_OK) == 0

internal fun deleteFile(path: String) {
    remove(path)
}

internal fun writeTextFile(
    path: String,
    text: String,
) {
    val f = fopen(path, "w") ?: error("cannot open $path for write")
    try {
        fputs(text, f)
    } finally {
        fclose(f)
    }
}

internal fun readTextFile(path: String): String {
    val f = fopen(path, "r") ?: error("cannot open $path for read")
    try {
        val sb = StringBuilder()
        memScoped {
            val bufSize = 4096
            val buf = allocArray<ByteVar>(bufSize)
            while (true) {
                val line = fgets(buf, bufSize, f) ?: break
                sb.append(line.toKString())
            }
        }
        return sb.toString()
    } finally {
        fclose(f)
    }
}

/** Parse `key=value` lines (the interop `config.properties` format) into a map. */
internal fun parseConfig(text: String): Map<String, String> =
    text
        .lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                null
            } else {
                val idx = trimmed.indexOf('=')
                if (idx <= 0) null else trimmed.substring(0, idx) to trimmed.substring(idx + 1)
            }
        }.toMap()
