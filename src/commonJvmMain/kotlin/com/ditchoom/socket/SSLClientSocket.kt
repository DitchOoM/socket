package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import com.ditchoom.socket.nio.ByteBufferClientSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.NoSuchAlgorithmException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SSLClientSocket(
    private val underlyingSocket: ClientToServerSocket,
) : ClientToServerSocket {
    private val byteBufferClientSocket = underlyingSocket as ByteBufferClientSocket<*>
    private val closeTimeout = 1.seconds
    private lateinit var engine: SSLEngine
    private var overflowEncryptedReadBuffer: BaseJvmBuffer? = null

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        tlsOptions: TlsOptions,
    ) {
        val context =
            try {
                SSLContext.getInstance("TLSv1.3")
            } catch (e: NoSuchAlgorithmException) {
                SSLContext.getInstance("TLSv1.2")
            }

        // Configure trust managers based on TlsOptions
        val trustManagers: Array<TrustManager>? =
            if (tlsOptions.isInsecure()) {
                // Use a trust-all manager for insecure mode (development/testing only)
                arrayOf(InsecureTrustManager())
            } else {
                // Use default trust managers (system trust store)
                null
            }

        context.init(null, trustManagers, null)
        engine = context.createSSLEngine(hostname, port)
        engine.useClientMode = true

        // Configure hostname verification based on TlsOptions
        if (tlsOptions.verifyHostname && hostname != null) {
            val sslParams = engine.sslParameters
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            engine.sslParameters = sslParams
        }

        engine.beginHandshake()
        underlyingSocket.open(port, timeout, hostname, tlsOptions)
        doHandshake(timeout)
    }

    /**
     * Trust manager that accepts all certificates.
     * WARNING: Only use for development and testing.
     */
    private class InsecureTrustManager : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) {}

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    override fun isOpen(): Boolean = underlyingSocket.isOpen()

    override suspend fun localPort(): Int = underlyingSocket.localPort()

    override suspend fun remotePort(): Int = underlyingSocket.remotePort()

    override suspend fun read(timeout: Duration): ReadBuffer = unwrap(timeout)

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int = wrap(buffer as BaseJvmBuffer, timeout)

    private suspend fun doHandshake(timeout: Duration) {
        var cachedBuffer: BaseJvmBuffer? = null
        val emptyBuffer = EMPTY_BUFFER as BaseJvmBuffer
        while (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(emptyBuffer, timeout)
                SSLEngineResult.HandshakeStatus.NEED_TASK ->
                    withContext(Dispatchers.IO) { engine.delegatedTask.run() }

                else -> { // UNWRAP + UNWRAP AGAIN
                    val dataRead =
                        if (cachedBuffer != null) {
                            cachedBuffer
                        } else {
                            val plainTextReadBuffer =
                                bufferFactory(engine.session.applicationBufferSize)
                            byteBufferClientSocket.read(plainTextReadBuffer, timeout)
                            plainTextReadBuffer.resetForRead()
                            plainTextReadBuffer
                        }
                    val result = engine.unwrap(dataRead.byteBuffer, emptyBuffer.byteBuffer)
                    cachedBuffer =
                        if (dataRead.byteBuffer.hasRemaining()) {
                            dataRead
                        } else {
                            null
                        }
                    when (checkNotNull(result.status)) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            cachedBuffer ?: continue
                            cachedBuffer.byteBuffer.compact()
                            byteBufferClientSocket.read(cachedBuffer, timeout)
                            cachedBuffer.resetForRead()
                        }

                        SSLEngineResult.Status.BUFFER_OVERFLOW ->
                            throw IllegalStateException("Unwrap Buffer Overflow")

                        SSLEngineResult.Status.CLOSED ->
                            throw IllegalStateException("SSLEngineResult Status Closed")

                        SSLEngineResult.Status.OK -> continue
                    }
                }
            }
        }
    }

    private suspend fun wrap(
        plainText: BaseJvmBuffer,
        timeout: Duration,
    ): Int {
        val encrypted = bufferFactory(engine.session.packetBufferSize)
        val result = engine.wrap(plainText.byteBuffer, encrypted.byteBuffer)
        when (result.status!!) {
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> throw IllegalStateException("SSL Engine Buffer Underflow - wrap")
            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                throw IllegalStateException("SSL Engine Buffer Overflow - wrap")
            }

            SSLEngineResult.Status.CLOSED,
            SSLEngineResult.Status.OK,
            -> {
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

    private fun bufferFactory(size: Int): BaseJvmBuffer = PlatformBuffer.allocate(size, AllocationZone.Direct) as BaseJvmBuffer

    private suspend fun unwrap(timeout: Duration): ReadBuffer {
        val byteBufferClientSocket = underlyingSocket as ByteBufferClientSocket<*>
        val encryptedReadBuffer =
            overflowEncryptedReadBuffer
                ?: bufferFactory(engine.session.packetBufferSize).also {
                    val bytesRead = byteBufferClientSocket.read(it, timeout)
                    if (bytesRead < 1) {
                        return EMPTY_BUFFER
                    }
                    it.resetForRead()
                }
        val plainTextReadBuffer = bufferFactory(engine.session.applicationBufferSize)
        while (encryptedReadBuffer.hasRemaining()) {
            val result =
                engine.unwrap(encryptedReadBuffer.byteBuffer, plainTextReadBuffer.byteBuffer)
            when (checkNotNull(result.status)) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    // plaintext buffer is too small, cache the encrypted read buffer so we can use it for next time
                    overflowEncryptedReadBuffer = encryptedReadBuffer
                    return slicePlainText(plainTextReadBuffer)
                }

                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    encryptedReadBuffer.byteBuffer.compact()
                    byteBufferClientSocket.read(encryptedReadBuffer, timeout)
                    encryptedReadBuffer.resetForRead()
                    overflowEncryptedReadBuffer = encryptedReadBuffer
                }

                SSLEngineResult.Status.OK -> {
                    overflowEncryptedReadBuffer = null
                }

                SSLEngineResult.Status.CLOSED -> {
                    overflowEncryptedReadBuffer = null
                    close()
                    return slicePlainText(plainTextReadBuffer)
                }
            }
        }
        return slicePlainText(plainTextReadBuffer)
    }

    private fun slicePlainText(plainText: BaseJvmBuffer): PlatformBuffer {
        val position = plainText.position()
        plainText.position(0)
        plainText.setLimit(position)
        val slicedBuffer = plainText.slice()
        slicedBuffer.position(slicedBuffer.limit())
        return slicedBuffer
    }

    override suspend fun close() {
        engine.closeOutbound()
        doHandshake(closeTimeout)
        underlyingSocket.close()
    }
}
