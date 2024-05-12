package com.ditchoom.socket

actual suspend fun readStats(
    port: Int,
    contains: String,
): List<String> {
//    delay(100)
//    yield()
//    if (!PortHelper().isPortOpenWithActualPort(port.convert())) {
//        return listOf("$port is still open")
//    }
    return emptyList()
}
