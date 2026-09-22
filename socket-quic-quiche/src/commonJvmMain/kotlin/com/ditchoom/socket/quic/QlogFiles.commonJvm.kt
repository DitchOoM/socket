package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QlogFile
import java.io.File

internal actual fun qlogFileBytes(path: String): Long = File(path).length()

internal actual fun deleteQlogFile(path: String): QlogDeletion {
    val file = File(path)
    return if (file.delete() || !file.exists()) QlogDeletion.Deleted else QlogDeletion.Failed
}

internal actual fun appendQlogNote(
    path: String,
    line: String,
) {
    runCatching { File(path).appendText(line + "\n") }
}

internal actual fun listQlogFiles(dir: String): List<QlogFile> =
    File(dir)
        .listFiles()
        .orEmpty()
        .filter { it.isFile }
        .sortedBy { it.lastModified() }
        .map { QlogFile(it.path, it.length()) }
