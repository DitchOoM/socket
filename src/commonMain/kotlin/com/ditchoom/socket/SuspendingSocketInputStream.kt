package com.ditchoom.socket

import com.ditchoom.buffer.FragmentedReadBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.data.Reader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.plus
import kotlin.time.Duration

/**
 * Non blocking, suspending socket input stream.
 */
class SuspendingSocketInputStream(
    private val readTimeout: Duration,
    private val reader: Reader,
    parentScope: CoroutineScope? = null
) {
    private var currentBuffer: ReadBuffer? = null
    private var deferredBuffer: Deferred<ReadBuffer?>? = null

    suspend fun readUnsignedByte() = sizedReadBuffer(UByte.SIZE_BYTES).readUnsignedByte()
    suspend fun readByte() = sizedReadBuffer(Byte.SIZE_BYTES).readByte()
    private val readAheadScope = if (parentScope != null) {
        val s = parentScope + Job()
        queueNextRead()
        s
    } else {
        null
    }

    private suspend fun readMaybeQueueNextRead(): ReadBuffer {
        val readAheadScope = readAheadScope
        return if (readAheadScope == null) {
            readFromReader()
        } else {
            val deferredBuffer = deferredBuffer
            if (deferredBuffer == null) {
                val b = readFromReader()
                queueNextRead()
                b
            } else {
                val queuedBuffer = deferredBuffer.await() ?: readFromReader()
                queueNextRead()
                queuedBuffer
            }
        }
    }

    suspend fun sizedReadBuffer(size: Int): ReadBuffer {
        if (size < 1) {
            return EMPTY_BUFFER
        }
        val currentBuffer = currentBuffer
        var fragmentedLocalBuffer = if (currentBuffer != null && currentBuffer.hasRemaining()) {
            currentBuffer
        } else {
            readMaybeQueueNextRead()
        }
        this.currentBuffer = fragmentedLocalBuffer
        if (fragmentedLocalBuffer.remaining() >= size) {
            return fragmentedLocalBuffer
        }

        // ensure remaining in local buffer at least the size we requested
        while (fragmentedLocalBuffer.remaining() < size) {
            val moreData = readMaybeQueueNextRead()
            fragmentedLocalBuffer = FragmentedReadBuffer(fragmentedLocalBuffer, moreData)
        }
        this.currentBuffer = fragmentedLocalBuffer
        return fragmentedLocalBuffer
    }

    private suspend fun readFromReader(): ReadBuffer {
        val buffer = reader.read(readTimeout)
        buffer.resetForRead()
        return buffer
    }

    private fun queueNextRead() {
        if (!reader.isOpen()) {
            return
        }

        this.deferredBuffer = readAheadScope?.async {
            try {
                readFromReader()
            } catch (e: SocketClosedException) {
                null
            }
        }
    }
}
