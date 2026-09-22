@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QlogFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

internal actual fun listQlogFiles(dir: String): List<QlogFile> {
    val handle = opendir(dir) ?: return emptyList()
    val found = mutableListOf<Pair<Long, QlogFile>>()
    try {
        memScoped {
            val st = alloc<stat>()
            while (true) {
                val entry = readdir(handle) ?: break
                val path = "$dir/${entry.pointed.d_name.toKString()}"
                if (stat(path, st.ptr) != 0 || (st.st_mode.toInt() and S_IFMT) != S_IFREG) continue
                found += st.st_mtimespec.tv_sec to QlogFile(path, st.st_size)
            }
        }
    } finally {
        closedir(handle)
    }
    return found.sortedBy { it.first }.map { it.second }
}
