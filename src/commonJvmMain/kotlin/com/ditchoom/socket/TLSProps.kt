package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult
import kotlin.time.Duration

class TLSProps(private val useClientMode: Boolean) {
    private val engine by lazy(LazyThreadSafetyMode.NONE) {
        val context = SSLContext.getDefault()
        val e = context.createSSLEngine()
        e.useClientMode = useClientMode
        e
    }
    private val applicationBufferSize = engine.session.applicationBufferSize
    private val encryptedWriteBuffer =
        PlatformBuffer.allocate(engine.session.packetBufferSize, AllocationZone.Direct) as JvmBuffer

    suspend fun write(plainText: ByteBuffer, socketWriter: suspend (ByteBuffer, Duration) -> Int, timeout: Duration): Int {
        val limit = plainText.limit()
        var index = plainText.position()
        var bytesWrittenTotal = 0
        while (plainText.hasRemaining()) {
            if (index + applicationBufferSize <= limit) {
                // try to avoid underflow by only reading the application data size
                plainText.limit(index + applicationBufferSize)
                val partialBuffer = plainText.slice().asReadOnlyBuffer()
                plainText.limit(limit)
                plainText.position(index + applicationBufferSize)
                index += applicationBufferSize
                val bytesWritten = writeInternal(partialBuffer, socketWriter, timeout)
                if (bytesWritten < 0) {
                    return -1
                }
                bytesWrittenTotal += bytesWritten
            } else {
                val bytesWritten = writeInternal(plainText, socketWriter, timeout)
                if (bytesWritten < 0) {
                    return -1
                }
                bytesWrittenTotal += bytesWritten
            }
        }
        return bytesWrittenTotal
    }

    private suspend fun writeInternal(plainText: ByteBuffer, socketWriter: suspend (ByteBuffer, Duration) -> Int, timeout: Duration): Int {
        var writtenBytes = 0
        while(plainText.hasRemaining()) {
            encryptedWriteBuffer.byteBuffer.clear()
            val engineResult = engine.wrap(plainText, encryptedWriteBuffer.byteBuffer)
            when(engineResult.status!!) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> throw IllegalStateException("Buffer Underflow on write")
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    var hasLargeEnoughBuffer = false
                    var writeBufferSize = encryptedWriteBuffer.capacity
                    lateinit var buffer: JvmBuffer
                    while (!hasLargeEnoughBuffer) {
                        writeBufferSize += engine.session.packetBufferSize
                        buffer =  PlatformBuffer.allocate(writeBufferSize, AllocationZone.Direct) as JvmBuffer
                        hasLargeEnoughBuffer = when (engine.wrap(plainText, buffer.byteBuffer).status!!) {
                            SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                                throw IllegalStateException("Buffer Underflow on write, after overflow")
                            SSLEngineResult.Status.OK -> {
                                true
                            }
                            SSLEngineResult.Status.CLOSED -> { return -1 }
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                                false
                            }
                        }
                    }
                    buffer.byteBuffer.flip()
                    writtenBytes += socketWriter(buffer.byteBuffer, timeout)
                }
                SSLEngineResult.Status.OK -> {
                    encryptedWriteBuffer.byteBuffer.flip()
                    socketWriter(encryptedWriteBuffer.byteBuffer, timeout)
                }
                SSLEngineResult.Status.CLOSED -> {
                    return -1
                }
            }
        }
        return writtenBytes
    }

    private val encryptedReadBuffer = PlatformBuffer.allocate(engine.session.packetBufferSize, AllocationZone.Direct) as JvmBuffer

    private var overflowedOnLastUnwrap = false

    suspend fun read(plainTextJvmBuffer: JvmBuffer, socketReader: suspend (ByteBuffer, Duration) -> Int, timeout: Duration): Int {
        val plainTextByteBuffer = plainTextJvmBuffer.byteBuffer
        encryptedReadBuffer.byteBuffer.clear()
        if (!overflowedOnLastUnwrap) {
            socketReader(encryptedReadBuffer.byteBuffer, timeout)
            overflowedOnLastUnwrap = false
        }

        val result = engine.unwrap(encryptedReadBuffer.byteBuffer, plainTextByteBuffer)
        when (result.status!!) {
            SSLEngineResult.Status.CLOSED -> return -1
            SSLEngineResult.Status.OK -> {}
            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                overflowedOnLastUnwrap = true
            }
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                val otherBytesConsumed = read(plainTextJvmBuffer, socketReader, timeout)
                if (otherBytesConsumed < 0) {
                    return -1
                }
                return result.bytesConsumed() + otherBytesConsumed
            }
        }
        return result.bytesConsumed()
    }

}