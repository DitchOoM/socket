package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A read that waits for the network never hangs while quiche holds bytes, a FIN or a reset for it, and a
 * read that quiche can only answer with an error still gets that error. Real quiche on both ends.
 *
 * A read parks without asking the loop whether quiche holds anything for its stream (the loop sweeps the
 * readable streams every turn and signals, hands off or marks what it finds). Each test below is a shape
 * where that rule could leave a read parked forever; every wait is bounded, so a hang fails the test in
 * seconds instead of timing out the suite.
 */
class QuicReadNeverHangsTest {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private val bound = 5.seconds

    private suspend fun QuicByteStream.sendBytes(count: Int) {
        val buf = BufferFactory.deterministic().allocate(count)
        repeat(count) { buf.writeByte((it and 0x7f).toByte()) }
        buf.resetForRead()
        try {
            writeFully(buf, bound)
        } finally {
            buf.freeNativeMemory()
        }
    }

    /** Reads until End or Reset, counting bytes; every read bounded. Returns the bytes and the verdict. */
    private suspend fun QuicByteStream.readToVerdict(): Pair<Int, ReadResult> {
        var got = 0
        while (true) {
            when (val r = read(bound)) {
                is ReadResult.Data -> {
                    got += r.buffer.remaining()
                    r.buffer.freeIfNeeded()
                }
                else -> return got to r
            }
        }
    }

    /**
     * A client stream is opened by writing one byte; the server accepts it, reads the byte, and runs
     * [script] on it. [client] then runs on the client's stream.
     */
    private fun scenario(
        script: suspend QuicByteStream.() -> Unit,
        client: suspend QuicScope.(QuicByteStream) -> Unit,
    ) = runQuicTest(timeout = 60.seconds) {
        try {
            val opts = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false)
            withQuicServer(port = 0, tlsConfig = tls, quicOptions = opts) {
                val serverJob =
                    launch {
                        connections {
                            val stream = acceptStream()
                            val first = stream.read(bound)
                            assertIs<ReadResult.Data>(first)
                            first.buffer.freeIfNeeded()
                            stream.script()
                            // Hold the connection open until the client is done with it.
                            kotlinx.coroutines.delay(bound)
                        }
                    }
                try {
                    commonJvmWithQuicConnection(
                        endpoint = QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
                        serverName = "localhost",
                        quicOptions = opts,
                        connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
                        timeout = 10.seconds,
                    ) {
                        val stream = openStream()
                        stream.sendBytes(1)
                        client(stream)
                    }
                } finally {
                    serverJob.cancel()
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            recordMissingNativeLib(QuicReadNeverHangsTest::class, e)
        }
    }

    @Test
    fun aFinWithNoBytesEndsTheRead() =
        scenario(script = { shutdownSend() }) { stream ->
            val (got, verdict) = stream.readToVerdict()
            assertEquals(0, got)
            assertIs<ReadResult.End>(verdict)
        }

    @Test
    fun aFinWithTheLastBytesDeliversThemThenEnds() =
        scenario(script = {
            sendBytes(100)
            shutdownSend()
        }) { stream ->
            val (got, verdict) = stream.readToVerdict()
            assertEquals(100, got)
            assertIs<ReadResult.End>(verdict)
        }

    @Test
    fun aResetAfterPartialDataDeliversTheDataThenReports() =
        scenario(script = {
            sendBytes(100)
            reset(0x10c)
        }) { stream ->
            val (_, verdict) = stream.readToVerdict()
            assertIs<ReadResult.Reset>(verdict)
        }

    @Test
    fun aStreamLargerThanTheReadBufferIsReadToItsEnd() =
        // The first chunk fills the 64 KiB read buffer, so quiche still holds bytes with no signal left:
        // the mark is what makes the next read ask.
        scenario(script = {
            sendBytes(LARGE)
            shutdownSend()
        }) { stream ->
            val (got, verdict) = stream.readToVerdict()
            assertEquals(LARGE, got)
            assertIs<ReadResult.End>(verdict)
        }

    @Test
    fun dataThatNeedsOurFlowControlCreditToArriveIsReadToItsEnd() =
        // Far more than the initial stream and connection windows: the sender stalls until the reader's
        // consumption credits it, so the reader must keep reading while the credit rides back.
        scenario(script = {
            sendBytes(HUGE)
            shutdownSend()
        }) { stream ->
            val (got, verdict) = stream.readToVerdict()
            assertEquals(HUGE, got)
            assertIs<ReadResult.End>(verdict)
        }

    @Test
    fun aReadThatTimedOutDoesNotStrandTheNextOne() =
        scenario(script = {
            kotlinx.coroutines.delay(600.milliseconds)
            sendBytes(100)
            shutdownSend()
        }) { stream ->
            // Times out while parked, with the server's bytes not sent yet.
            assertFailsWith<TimeoutCancellationException> { stream.read(100.milliseconds) }
            val (got, verdict) = stream.readToVerdict()
            assertEquals(100, got)
            assertIs<ReadResult.End>(verdict)
        }

    @Test
    fun aReadOfALocallyOpenedUnidirectionalStreamFailsWithQuichesTypedError() =
        runQuicTest(timeout = 30.seconds) {
            try {
                val opts = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false)
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = opts) {
                    val serverJob = launch { connections { kotlinx.coroutines.delay(bound) } }
                    try {
                        commonJvmWithQuicConnection(
                            endpoint = QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
                            serverName = "localhost",
                            quicOptions = opts,
                            connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
                            timeout = 10.seconds,
                        ) {
                            val stream = openUniStream()
                            val failure = assertFailsWith<QuicStreamReadException> { withTimeout(bound) { stream.read(bound) } }
                            assertEquals(QuicStreamReadError.InvalidStreamState, failure.error)
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(QuicReadNeverHangsTest::class, e)
            }
        }

    private companion object {
        const val LARGE = 200 * 1024
        const val HUGE = 6 * 1024 * 1024
    }
}
