package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.socket.nio.ByteBufferClientSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SSLClientSocket(private val underlyingSocket: ClientToServerSocket) : ClientToServerSocket {

    private val closeTimeout = 1.seconds
    private lateinit var engine: SSLEngine

    // unwrap parameters
    lateinit var encryptedReadBuffer: JvmBuffer
    lateinit var plainTextReadBuffer: JvmBuffer
    private var shouldReallocatePlainTextBuffer = true

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
    ) {
        val socketOptionsLocal = underlyingSocket.open(port, timeout, hostname)
        val context = try {
            SSLContext.getInstance("TLSv1.3")
        } catch (e: NoSuchAlgorithmException) {
            SSLContext.getInstance("TLSv1.2")
        }
        context.init(null, null, null)
        engine = context.createSSLEngine(hostname, port)
        engine.useClientMode = true
        engine.beginHandshake()
        doHandshake(timeout)
        return socketOptionsLocal
    }

    override fun isOpen(): Boolean = underlyingSocket.isOpen()

    override suspend fun localPort(): Int = underlyingSocket.localPort()

    override suspend fun remotePort(): Int = underlyingSocket.remotePort()

    override suspend fun read(timeout: Duration): ReadBuffer = unwrap(timeout)

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int =
        wrap(buffer as JvmBuffer, timeout)

    private suspend fun doHandshake(timeout: Duration) {
        var cachedBuffer: PlatformBuffer? = null
        val wrapBuffer = EMPTY_BUFFER as JvmBuffer
        while (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(wrapBuffer, timeout)
                SSLEngineResult.HandshakeStatus.NEED_TASK -> withContext(Dispatchers.IO) { engine.delegatedTask.run() }
                else -> {
                    val dataRead = if (cachedBuffer != null) {
                        cachedBuffer
                    } else {
                        val data = underlyingSocket.read(timeout)
                        data.resetForRead()
                        data
                    }
                    val byteBuffer = (dataRead as JvmBuffer).byteBuffer
                    val result = engine.unwrap(byteBuffer, wrapBuffer.byteBuffer)
                    cachedBuffer = if (byteBuffer.hasRemaining()) {
                        dataRead
                    } else {
                        null
                    }
                    when (result.status!!) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            cachedBuffer = null
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> throw IllegalStateException("Unwrap Buffer Overflow")
                        SSLEngineResult.Status.CLOSED -> throw IllegalStateException("SSLEngineResult Status Closed")
                        SSLEngineResult.Status.OK -> continue
                    }
                }
            }
        }
    }

    private suspend fun wrap(plainText: JvmBuffer, timeout: Duration): Int {
        val encrypted = PlatformBuffer.allocate(
            engine.session.packetBufferSize,
            AllocationZone.Direct
        ) as JvmBuffer
        val result = engine.wrap(plainText.byteBuffer, encrypted.byteBuffer)
        when (result.status!!) {
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> throw IllegalStateException("SSL Engine Buffer Underflow - wrap")
            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                throw IllegalStateException("SSL Engine Buffer Overflow - wrap")
            }
            SSLEngineResult.Status.CLOSED,
            SSLEngineResult.Status.OK -> {
                encrypted.resetForRead()
                var writtenBytes = 0
                while (encrypted.hasRemaining()) {
                    val bytesWrote = underlyingSocket.write(encrypted, timeout)
                    if (bytesWrote < 0) {
                        return -1
                    }
                    writtenBytes += bytesWrote
                }
                return writtenBytes
            }
        }
    }

    private suspend fun unwrap(timeout: Duration): ReadBuffer {
        if (!this::encryptedReadBuffer.isInitialized) {
            encryptedReadBuffer = PlatformBuffer.allocate(
                engine.session.packetBufferSize,
                AllocationZone.Direct
            ) as JvmBuffer
        }
        if (shouldReallocatePlainTextBuffer || !this::plainTextReadBuffer.isInitialized) {
            plainTextReadBuffer = PlatformBuffer.allocate(
                engine.session.applicationBufferSize,
                AllocationZone.Direct
            ) as JvmBuffer
        }
        var exitReadLoop = false
        val byteBufferClientSocket = underlyingSocket as ByteBufferClientSocket<*>
        while (!exitReadLoop) {
            val bytesRead = byteBufferClientSocket.read(encryptedReadBuffer, timeout)
            if (bytesRead > 0) {
                encryptedReadBuffer.resetForRead()
                while (encryptedReadBuffer.hasRemaining()) {
                    val peerNetData = encryptedReadBuffer.byteBuffer
                    val peerAppData = plainTextReadBuffer.byteBuffer
                    val result = engine.unwrap(peerNetData, peerAppData)
                    when (result.status) {
                        SSLEngineResult.Status.OK -> {
                            exitReadLoop = true
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            peerNetData.compact()
                            return JvmBuffer(plainTextReadBuffer.byteBuffer.asReadOnlyBuffer())
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            peerNetData.compact()
                            break
                        }
                        SSLEngineResult.Status.CLOSED -> {
                            exitReadLoop = true
                            close()
                            break
                        }
                        null -> {}
                    }
                }
            } else {
                encryptedReadBuffer.resetForWrite()
                break
            }
        }
        return JvmBuffer(plainTextReadBuffer.byteBuffer.asReadOnlyBuffer())
    }

    override suspend fun close() {
        engine.closeOutbound()
        doHandshake(closeTimeout)
        underlyingSocket.close()
    }
}
