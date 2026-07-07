package com.ditchoom.socket

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.unwrapFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.NoSuchAlgorithmException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
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
    private val bufferFactory: BufferFactory = BufferFactory.Default,
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

        // SNI + hostname verification must be configured through a single
        // SSLParameters round-trip. Assigning `engine.sslParameters = sslParams`
        // with `serverNames = null` wipes the implicit SNI that
        // createSSLEngine(host, port) sets, which caused handshakes against
        // SNI-strict hosts (example.com, cloudflare.com) to fail. RFC 6066
        // §3 forbids SNI values that look like IP literals, so guard on that
        // when populating serverNames.
        if (hostname != null) {
            val sslParams = engine.sslParameters
            if (config.verifyHostname) {
                sslParams.endpointIdentificationAlgorithm = "HTTPS"
            }
            if (!hostname.isIpLiteral()) {
                sslParams.serverNames = listOf(SNIHostName(hostname))
            }
            engine.sslParameters = sslParams
        }

        engine.beginHandshake()
        try {
            doHandshake(timeout)
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            throw SSLHandshakeFailedException(e.message ?: "TLS handshake failed", e)
        } catch (e: javax.net.ssl.SSLException) {
            throw SSLProtocolException(e.message ?: "TLS error during handshake", e)
        }
    }

    suspend fun wrap(
        plainText: BaseJvmBuffer,
        timeout: Duration,
    ): Int {
        val encrypted = allocateBuffer(engine.session.packetBufferSize)
        try {
            val result =
                try {
                    engine.wrap(plainText.byteBuffer, encrypted.byteBuffer)
                } catch (e: javax.net.ssl.SSLHandshakeException) {
                    throw SSLHandshakeFailedException(e.message ?: "TLS handshake failed", e)
                } catch (e: javax.net.ssl.SSLException) {
                    throw SSLProtocolException(e.message ?: "TLS wrap error", e)
                }
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
        } finally {
            encrypted.freeIfNeeded()
        }
    }

    suspend fun unwrap(timeout: Duration): ReadBuffer {
        val plainTextReadBuffer = allocateBuffer(engine.session.applicationBufferSize)
        // Loop until we produce application data. TLS 1.3 may send post-handshake
        // messages (e.g. NewSessionTicket) that unwrap to 0 application bytes.
        // We must retry rather than returning empty, which callers treat as EOF.
        while (true) {
            val encryptedReadBuffer =
                overflowEncryptedReadBuffer
                    ?: allocateBuffer(engine.session.packetBufferSize).also {
                        val bytesRead = rawRead(it, timeout)
                        if (bytesRead < 1) {
                            return EMPTY_BUFFER
                        }
                        it.resetForRead()
                    }
            while (encryptedReadBuffer.hasRemaining()) {
                val result =
                    try {
                        engine.unwrap(encryptedReadBuffer.byteBuffer, plainTextReadBuffer.byteBuffer)
                    } catch (e: javax.net.ssl.SSLHandshakeException) {
                        throw SSLHandshakeFailedException(e.message ?: "TLS handshake failed", e)
                    } catch (e: javax.net.ssl.SSLException) {
                        throw SSLProtocolException(e.message ?: "TLS unwrap error", e)
                    }
                when (checkNotNull(result.status)) {
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        overflowEncryptedReadBuffer = encryptedReadBuffer
                        return slicePlainText(plainTextReadBuffer)
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        encryptedReadBuffer.byteBuffer.compactCompat()
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
            // If we produced application data, return it
            if (plainTextReadBuffer.position() > 0) {
                return slicePlainText(plainTextReadBuffer)
            }
            // No application data produced (e.g. TLS 1.3 NewSessionTicket) — read more
        }
    }

    fun closeOutbound() {
        if (::engine.isInitialized) {
            engine.closeOutbound()
        }
    }

    private suspend fun doHandshake(timeout: Duration) {
        var cachedBuffer: BaseJvmBuffer? = null
        val emptyBuffer = EMPTY_BUFFER.unwrapFully() as BaseJvmBuffer
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
                                allocateBuffer(engine.session.applicationBufferSize)
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
                            cachedBuffer.byteBuffer.compactCompat()
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

    private fun allocateBuffer(size: Int): BaseJvmBuffer = bufferFactory.allocate(size).unwrapFully() as BaseJvmBuffer

    private fun slicePlainText(plainText: BaseJvmBuffer): PlatformBuffer {
        plainText.resetForRead()
        return plainText.slice()
    }

    private fun String.isIpLiteral(): Boolean {
        if (startsWith('[') && endsWith(']')) return true // bracketed IPv6
        if (contains(':')) return true // unbracketed IPv6
        return split('.').let { parts ->
            parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all(Char::isDigit) }
        }
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

/**
 * In-place compaction equivalent to [java.nio.ByteBuffer.compact], implemented so it never routes
 * through the platform's `DirectByteBuffer.compact()`.
 *
 * The TLS handshake/unwrap buffers come from the caller's BufferFactory. The library default is
 * `BufferFactory.deterministic()`, which on Android has no `Unsafe.invokeCleaner` and so falls back
 * to `Unsafe.allocateMemory`, handing back an **array-less** native `DirectByteBuffer` (`hb == null`).
 * Android's libcore `DirectByteBuffer.compact()` reaches a `System.arraycopy` against that null
 * heap-backing array and throws `NullPointerException: src == null`. (Plain `allocateDirect` buffers
 * are array-backed on ART and compact fine — only the deterministic/unsafe path trips; on HotSpot
 * every direct buffer compacts via native `Unsafe.copyMemory`, so the JVM never sees this.) Because
 * TLS records routinely span multiple socket reads, the SSLEngine returns BUFFER_UNDERFLOW and we
 * compact — so the stock call crashes every WSS handshake/read on Android while working on the JVM.
 *
 * The move is a single bulk `put(duplicate())`: the duplicate is a view over the unread region
 * `[position, limit)` and `clear()` repositions the receiver at the front, so `put` copies the
 * remaining bytes down to `[0, remaining)` via the buffer's native-address path (`memmove` on
 * direct buffers), never the heap-array `System.arraycopy`. The destination region strictly
 * precedes the source region, so even a plain forward copy can never clobber an unread byte.
 */
internal fun ByteBuffer.compactCompat() {
    val rem = remaining()
    if (position() > 0) {
        val src = duplicate() // shares memory; its [position, limit) is the unread region
        clear() // position = 0, limit = capacity
        put(src) // copies rem bytes into [0, rem); advances position to rem
    } else {
        position(rem)
    }
    limit(capacity())
}
