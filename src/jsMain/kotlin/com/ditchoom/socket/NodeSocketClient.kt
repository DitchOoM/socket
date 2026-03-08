package com.ditchoom.socket

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.time.Duration

/**
 * System CA certificate paths for common Linux distributions.
 * Same paths used by LinuxClientSocket for native TLS.
 */
private val SYSTEM_CA_PATHS =
    arrayOf(
        "/etc/ssl/certs/ca-certificates.crt", // Debian/Ubuntu
        "/etc/pki/tls/certs/ca-bundle.crt", // RHEL/Fedora/CentOS
        "/etc/ssl/ca-bundle.pem", // OpenSUSE
        "/etc/ssl/cert.pem", // Alpine/Arch
    )

// Dynamic require() calls to avoid webpack trying to bundle Node.js-only modules.
// These are only called at runtime on Node.js (guarded by isNodeJs / TLS checks).
private fun fsExistsSync(path: String): Boolean = js("require('fs').existsSync(path)") as Boolean

private fun fsReadFileSync(
    path: String,
    encoding: String,
): String = js("require('fs').readFileSync(path, encoding)") as String

@Suppress("UNCHECKED_CAST")
private fun tlsRootCertificates(): Array<String> = js("require('tls').rootCertificates") as Array<String>

/**
 * Lazily loads system CA certificates combined with Node's built-in root certificates.
 * Returns null if no system CA file is found (falls back to Node's built-in bundle).
 */
private val systemCaCertificates: Array<String>? by lazy {
    // Find the first existing system CA bundle
    val systemCaPath =
        SYSTEM_CA_PATHS.firstOrNull { path ->
            try {
                fsExistsSync(path)
            } catch (_: Throwable) {
                false
            }
        } ?: return@lazy null

    // Read and parse the PEM file into individual certificates
    val pemContent =
        try {
            fsReadFileSync(systemCaPath, "utf8")
        } catch (_: Throwable) {
            return@lazy null
        }

    val systemCerts =
        pemContent
            .split("-----END CERTIFICATE-----")
            .map { it.trim() }
            .filter { it.contains("-----BEGIN CERTIFICATE-----") }
            .map { it.substringFrom("-----BEGIN CERTIFICATE-----") + "\n-----END CERTIFICATE-----" }

    // Combine with Node's built-in root certificates, deduplicate
    val nodeCerts =
        try {
            tlsRootCertificates().toSet()
        } catch (_: Throwable) {
            emptySet()
        }

    val combined = LinkedHashSet<String>(nodeCerts.size + systemCerts.size)
    combined.addAll(nodeCerts)
    combined.addAll(systemCerts)
    combined.toTypedArray()
}

/** Helper to extract from a marker within a string */
private fun String.substringFrom(marker: String): String {
    val idx = indexOf(marker)
    return if (idx >= 0) substring(idx) else this
}

open class NodeSocket : ClientSocket {
    internal var isClosed = true
    internal var netSocket: Socket? = null
    internal val incomingMessageChannel = Channel<SocketDataRead<ReadBuffer>>()
    internal var hadTransmissionError = false
    private val writeMutex = Mutex()

    override fun isOpen(): Boolean {
        val socket = netSocket ?: return false
        return !isClosed || socket.remoteAddress != null
    }

    override suspend fun localPort() = netSocket?.localPort ?: -1

    override suspend fun remotePort() = netSocket?.remotePort ?: -1

    override suspend fun read(timeout: Duration): ReadBuffer {
        val socket = netSocket
        if (socket == null || !isOpen()) {
            throw SocketClosedException("Socket closed. transmissionError=$hadTransmissionError")
        }
        socket.resume()
        val message =
            withTimeout(timeout) {
                try {
                    incomingMessageChannel.receive()
                } catch (e: ClosedReceiveChannelException) {
                    throw SocketClosedException(
                        "Socket is already closed. transmissionError=$hadTransmissionError",
                        e,
                    )
                }
            }
        if (message.bytesRead < 0 || !isOpen()) {
            throw SocketClosedException(
                "Received ${message.bytesRead} from server indicating a socket close. transmissionError=$hadTransmissionError",
            )
        }
        message.result.position(message.bytesRead)
        return message.result
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val socket = netSocket
        if (socket == null || !isOpen()) {
            throw SocketClosedException("Socket is closed. transmissionError=$hadTransmissionError")
        }
        val bytesToWrite = buffer.remaining()
        val jsBuffer =
            when (buffer) {
                is JsBuffer -> buffer
                is PlatformBuffer -> buffer.unwrap() as JsBuffer
                else -> null
            }
        val dataToWrite =
            if (jsBuffer != null) {
                val array = jsBuffer.buffer
                Uint8Array(array.buffer, array.byteOffset + buffer.position(), bytesToWrite)
            } else {
                // Fallback for non-PlatformBuffer types (e.g. TrackedSlice)
                val savedPos = buffer.position()
                val bytes = buffer.readByteArray(bytesToWrite)
                buffer.position(savedPos)
                Uint8Array(bytes.unsafeCast<Int8Array>().buffer, 0, bytesToWrite)
            }
        writeMutex.withLock { socket.write(dataToWrite) }
        buffer.position(buffer.position() + bytesToWrite)
        return bytesToWrite
    }

    fun cleanSocket(socket: Socket) {
        incomingMessageChannel.close()
        socket.removeAllListeners()
        socket.end {}
        socket.destroy()
        socket.unref()
        isClosed = true
    }

    override suspend fun close() {
        val socket = netSocket ?: return
        cleanSocket(socket)
    }
}

class NodeClientSocket :
    NodeSocket(),
    ClientToServerSocket {
    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions,
    ) {
        val useTls = socketOptions.tls != null
        val rejectUnauthorized = socketOptions.tls?.let { it.verifyCertificates && !it.allowSelfSigned } ?: true
        // Load system CAs when connecting with TLS and certificate verification is enabled
        val caCerts = if (useTls && rejectUnauthorized) systemCaCertificates else null
        // Set servername explicitly for SNI (Server Name Indication)
        val options =
            Options(
                port = port,
                host = hostname,
                onread = null,
                rejectUnauthorized = rejectUnauthorized,
                servername = hostname,
                ca = caCerts,
            )
        val netSocket = connect(useTls, options, timeout)
        isClosed = false
        this@NodeClientSocket.netSocket = netSocket
        netSocket.on("data") { data ->
            val result = int8ArrayOf(data)
            val buffer = JsBuffer(result)
            buffer.position(result.length)
            buffer.resetForRead()
            incomingMessageChannel.trySend(SocketDataRead(buffer, result.length))
        }
        netSocket.on("close") { transmissionError ->
            hadTransmissionError = transmissionError.unsafeCast<Boolean>()
            cleanSocket(netSocket)
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun int8ArrayOf(
        @Suppress("UNUSED_PARAMETER") obj: Any,
    ): Int8Array =
        js(
            """
            if (Buffer.isBuffer(obj)) {
                // Zero-copy view into the Node.js Buffer
                return new Int8Array(obj.buffer, obj.byteOffset, obj.byteLength)
            } else {
                var buf = Buffer.from(obj);
                return new Int8Array(buf.buffer, buf.byteOffset, buf.byteLength)
            }
        """,
        ) as Int8Array
}
