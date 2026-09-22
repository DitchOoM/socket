@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.remove
import platform.posix.stat

internal actual fun qlogFileBytes(path: String): Long =
    memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) == 0) st.st_size else 0L
    }

internal actual fun deleteQlogFile(path: String): QlogDeletion =
    if (remove(path) == 0 || errno == ENOENT) QlogDeletion.Deleted else QlogDeletion.Failed

internal actual fun appendQlogNote(
    path: String,
    line: String,
) {
    val file = fopen(path, "a") ?: return
    try {
        fputs(line + "\n", file)
    } finally {
        fclose(file)
    }
}
