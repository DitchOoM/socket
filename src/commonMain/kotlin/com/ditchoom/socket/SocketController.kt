package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer

interface SocketController : Reader<ReadBuffer>, Writer<PlatformBuffer>, SuspendCloseable {
    override fun isOpen(): Boolean

    /**
     * Suspends the caller until the socket connection has fully closed.
     */
    suspend fun awaitClose(): SocketException
}