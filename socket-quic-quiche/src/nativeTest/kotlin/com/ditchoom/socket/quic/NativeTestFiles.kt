@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.DT_REG
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir

/** POSIX file access for native test members that inspect what a connection left on disk. */
internal object NativeTestFiles {
    /** A new, empty directory under `TMPDIR` (or `/tmp`), as an absolute path. */
    fun newDirectory(prefix: String): String =
        memScoped {
            val base = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"
            val template = "$base/$prefix-XXXXXX".cstr.getPointer(this)
            mkdtemp(template)?.toKString() ?: throw AssertionError("mkdtemp failed under $base")
        }

    /** Every regular file directly in [dir], by name, with its text. */
    fun filesIn(dir: String): Map<String, String> {
        val stream = opendir(dir) ?: throw AssertionError("cannot open directory $dir")
        val names = mutableListOf<String>()
        try {
            while (true) {
                val entry = readdir(stream)?.pointed ?: break
                if (entry.d_type.toInt() == DT_REG) names += entry.d_name.toKString()
            }
        } finally {
            closedir(stream)
        }
        return names.associateWith { readText("$dir/$it") }
    }

    private fun readText(path: String): String =
        memScoped {
            val file = fopen(path, "r") ?: throw AssertionError("cannot open $path")
            try {
                val chunkSize = 65536
                val chunk = allocArray<ByteVar>(chunkSize)
                var bytes = ByteArray(0)
                while (true) {
                    val n = fread(chunk, 1.convert(), chunkSize.convert(), file).toInt()
                    if (n <= 0) break
                    bytes += chunk.readBytes(n)
                }
                bytes.decodeToString()
            } finally {
                fclose(file)
            }
        }
}
