package com.ditchoom.socket.mainthread

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicServer
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.read
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** The server and client options and certificates the QUIC-family scenarios share. */
internal object QuicFixtures {
    val options = QuicOptions(alpnProtocols = listOf("main-like"), verifyPeer = false, idleTimeout = 10.seconds)

    fun tlsConfig() = QuicTlsConfig(certChainPath = resourcePath("certs/cert.crt"), privKeyPath = resourcePath("certs/cert.key"))

    fun resourcePath(name: String): String {
        val url = QuicFixtures::class.java.classLoader.getResource(name) ?: error("missing test resource $name")
        return java.io.File(url.toURI()).absolutePath
    }

    fun text(s: String): PlatformBuffer =
        BufferFactory.deterministic().allocate(s.length.coerceAtLeast(1)).apply {
            writeString(s, Charset.UTF8)
            resetForRead()
        }

    /** Serves every connection: echo the first stream's first chunk back prefixed with `echo:`, then FIN. */
    suspend fun QuicServer.serveEcho() =
        connections {
            val stream = acceptStream()
            val data = stream.read(5.seconds)
            if (data is ReadResult.Data) {
                try {
                    stream.writeFully(text("echo:"), 5.seconds)
                    stream.writeFully(data.buffer, 5.seconds)
                } finally {
                    data.buffer.freeIfNeeded()
                }
            }
            stream.close()
        }

    /** Client half: open a stream, write, read the echo, close — asserting each step resumes on the main-like thread. */
    suspend fun QuicScope.echoRoundTrip(checkThread: Boolean = true) {
        val stream = openStream()
        if (checkThread) assertOnMainLike("openStream")
        stream.writeFully(text("hello"), 5.seconds)
        if (checkThread) assertOnMainLike("stream write")
        val reply = StringBuilder()
        while (reply.length < "echo:hello".length) {
            val read = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
            reply.append(assertIs<ScopedRead.Data<String>>(read).value)
        }
        if (checkThread) assertOnMainLike("stream read")
        assertEquals("echo:hello", reply.toString())
        stream.close()
        if (checkThread) assertOnMainLike("stream close")
    }
}
