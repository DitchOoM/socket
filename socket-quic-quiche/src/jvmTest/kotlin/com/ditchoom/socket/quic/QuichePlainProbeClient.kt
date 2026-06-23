package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

/**
 * DIAGNOSTIC PROBE (anti-amplification interop hunt) — **not a unit test**, gated by the
 * `nw.plain.client` system property so an ordinary `jvmTest` skips it via a JUnit assumption.
 *
 * The raw **quiche** client half of the plain-listener probe: it dials the **plain (non-group)**
 * Network.framework QUIC listener stood up by `NwPlainListenerProbe` (Apple K/N), presenting an
 * **RSA** cert — the flight size that deadlocks the connection-group server. If `withQuicConnection`
 * completes (the block runs), a plain NW listener does NOT under-credit anti-amplification, so the
 * bug is group-specific. If it throws `IdleTimeout`, the plain listener deadlocks too → the
 * under-crediting is libquic-wide (the listener→connection handoff), independent of the server model.
 *
 * Reads `port` from the file named by the `nw.plain.configFile` system property.
 */
class QuichePlainProbeClient {
    @Test
    fun handshakeAgainstPlainListener() {
        assumeTrue(
            "QuichePlainProbeClient only runs with -Dnw.plain.client=true (plain-listener amplification probe)",
            System.getProperty("nw.plain.client") == "true",
        )
        val cfg = File(System.getProperty("nw.plain.configFile") ?: error("nw.plain.configFile not set"))
        val props = Properties().apply { cfg.inputStream().use { load(it) } }
        val port = (props.getProperty("port") ?: error("config has no port")).trim().toInt()
        println("PLAIN_CLIENT_START port=$port")

        val options =
            QuicOptions(
                alpnProtocols = listOf("hq-interop"),
                verifyPeer = false,
                idleTimeout = 10.seconds,
            )

        runBlocking {
            withContext(Dispatchers.Default) {
                withQuicConnection("localhost", port, options, timeout = 15.seconds) {
                    // Reaching here means the QUIC handshake COMPLETED against the plain NW listener.
                    println("PLAIN_CLIENT_CONNECTED") // <-- the diagnostic signal
                    runCatching {
                        val stream = openStream()
                        val buf = BufferFactory.Default.allocate(10)
                        buf.writeString("plain-ping", Charset.UTF8)
                        buf.resetForRead()
                        stream.write(buf, 5.seconds)
                        (stream as HalfCloseable).shutdownSend()
                        val r = stream.read(5.seconds)
                        val echo = if (r is ReadResult.Data) r.buffer.readString(r.buffer.remaining(), Charset.UTF8) else "<$r>"
                        println("PLAIN_CLIENT_STREAM echo=$echo")
                        stream.close()
                    }.onFailure { println("PLAIN_CLIENT_STREAM_FAILED ${it.message}") }
                }
                println("PLAIN_CLIENT_OK")
            }
        }
    }
}
