@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic.core.udp

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

internal actual fun readTable(path: String): TableRead =
    memScoped {
        val file = fopen(path, "r") ?: return@memScoped TableRead.Unreadable
        val line = allocArray<ByteVar>(LINE_BYTES)
        val lines = ArrayList<String>()
        while (fgets(line, LINE_BYTES, file) != null) lines.add(line.toKString().trimEnd('\n'))
        fclose(file)
        TableRead.Lines(lines)
    }

private const val LINE_BYTES = 512
