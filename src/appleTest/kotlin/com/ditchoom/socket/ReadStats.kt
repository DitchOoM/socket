package com.ditchoom.socket

import cocoapods.SocketWrapper.PortHelper
import kotlinx.cinterop.convert
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

actual suspend fun readStats(port: Int, contains: String): List<String> {
//    delay(100)
//    yield()
//    if (!PortHelper().isPortOpenWithActualPort(port.convert())) {
//        return listOf("$port is still open")
//    }
    return emptyList()
}
