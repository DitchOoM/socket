package com.ditchoom.socket

import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer

interface SocketController :
    Reader,
    Writer,
    SuspendCloseable {
    override fun isOpen(): Boolean

    suspend fun localPort(): Int

    suspend fun remotePort(): Int
}
