@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface SocketController : Reader<ReadBuffer>, Writer<PlatformBuffer>, SuspendCloseable {
    override fun isOpen(): Boolean

    /**
     * Suspends the caller until the socket connection has fully closed.
     */
    suspend fun awaitClose()
}