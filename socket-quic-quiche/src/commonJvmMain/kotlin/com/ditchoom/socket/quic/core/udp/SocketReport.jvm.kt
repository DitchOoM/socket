package com.ditchoom.socket.quic.core.udp

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun readTable(path: String): TableRead =
    try {
        TableRead.Lines(Files.readAllLines(Paths.get(path)))
    } catch (unreadable: IOException) {
        TableRead.Unreadable
    }
