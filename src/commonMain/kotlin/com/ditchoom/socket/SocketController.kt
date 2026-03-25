package com.ditchoom.socket

import com.ditchoom.data.Reader
import com.ditchoom.data.Writer

interface SocketController :
    Reader,
    Writer {
    override fun isOpen(): Boolean

    suspend fun localPort(): Int

    suspend fun remotePort(): Int

    suspend fun close()
}
