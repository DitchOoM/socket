package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
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

/**
 * Standalone TLS handler using SSLEngine, designed for composition rather than decoration.
 *
 * Takes lambda read/write functions to access the raw TCP socket, enabling:
 * - Independent testing of TLS handshake/wrap/unwrap
 * - No decorator pattern — handler is a field inside the socket base class
 * - Clean lifecycle: created in open() after TCP connect, not at allocate() time
 */
internal class JvmTlsHandler(
    private val config: TlsConfig,
    private val hostname: String?,
    private val port: Int,
    private val rawRead: suspend (BaseJvmBuffer, Duration) -> Int,
    private val rawWrite: suspend (ReadBuffer, Duration) -> Int,
) {
    private lateinit var engine: SSLEngine
    private var overflowEncryptedReadBuffer: BaseJvmBuffer? = null
    val closeTimeout = 1.seconds

    suspend fun handshake(timeout: Duration) {
        val context =
            try {
                SSLContext.getInstance("TLSv1.3")
            } catch (e: NoSuchAlgorithmException) {
                SSLContext.getInstance("TLSv1.2")
            }

        val trustManagers: Array<TrustManager>? =
            if (config.isInsecure()) {
                arrayOf(InsecureTrustManager())
            } else {
                null
            }

        context.init(null, trustManagers, null)
        engine = context.createSSLEngine(hostname, port)
        engine.useClientMode = true

        if (config.verifyHostname && hostname != null) {
            val sslParams = engine.sslParameters
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            engine.sslParameters = sslParams
        }

        engine.beginHandshake()
        doHandshake(timeout)
    }

    suspend fun wrap(
        plainText: BaseJvmBuffer,
        timeout: Duration,
    ): Int {
        val encrypted = bufferFactory(engine.session.packetBufferSize)
        val result = engine.wrap(plainText.byteBuffer, encrypted.byteBuffer)
        when (result.status!!) {
            SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                throw IllegalStateException("SSL Engine Buffer Underflow - wrap")
            SSLEngineResult.Status.BUFFER_OVERFLOW ->
                throw IllegalStateException("SSL Engine Buffer Overflow - wrap")
            SSLEngineResult.Status.CLOSED,
            SSLEngineResult.Status.OK,
            -> {
                encrypted.resetForRead()
                var writtenBytes = 0
                while (encrypted.hasRemaining()) {
                    val bytesWrote = rawWrite(encrypted, timeout)
                    if (bytesWrote < 0) {
                        return -1
                    }
                    writtenBytes += bytesWrote
                }
                return writtenBytes
            }
        }
    }

    suspend fun unwrap(timeout: Duration): ReadBuffer {
        val encryptedReadBuffer =
            overflowEncryptedReadBuffer
                ?: bufferFactory(engine.session.packetBufferSize).also {
                    val bytesRead = rawRead(it, timeout)
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
                    overflowEncryptedReadBuffer = encryptedReadBuffer
                    return slicePlainText(plainTextReadBuffer)
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    encryptedReadBuffer.byteBuffer.compact()
                    rawRead(encryptedReadBuffer, timeout)
                    encryptedReadBuffer.resetForRead()
                    overflowEncryptedReadBuffer = encryptedReadBuffer
                }
                SSLEngineResult.Status.OK -> {
                    overflowEncryptedReadBuffer = null
                }
                SSLEngineResult.Status.CLOSED -> {
                    overflowEncryptedReadBuffer = null
                    return slicePlainText(plainTextReadBuffer)
                }
            }
        }
        return slicePlainText(plainTextReadBuffer)
    }

    fun closeOutbound() {
        if (::engine.isInitialized) {
            engine.closeOutbound()
        }
    }

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
                            rawRead(plainTextReadBuffer, timeout)
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
                            rawRead(cachedBuffer, timeout)
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

    private fun bufferFactory(size: Int): BaseJvmBuffer = PlatformBuffer.allocate(size, AllocationZone.Direct) as BaseJvmBuffer

    private fun slicePlainText(plainText: BaseJvmBuffer): PlatformBuffer {
        val position = plainText.position()
        plainText.position(0)
        plainText.setLimit(position)
        val slicedBuffer = plainText.slice()
        slicedBuffer.position(slicedBuffer.limit())
        return slicedBuffer
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
}
