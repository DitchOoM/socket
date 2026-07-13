package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic regression test for the #179 recv_info use-after-free (JVM/JNI).
 *
 * The bug: [JvmQuicServer.close] freed the per-source recv_info cache while a driver that had been
 * dropped from the [connectionsByDcid] *routing* table — but whose run loop was still draining a
 * buffered `RecvPacket` — could still `connRecv` that cached recv_info. It reproduced only as a rare
 * native SIGSEGV because it needs a precise interleaving of a de-route and a live `connRecv`.
 *
 * This test forces that exact interleaving with two seams instead of racing a native packet:
 *  - a [GatedConnRecvQuicheApi] freezes the server's driver run loop *inside* one post-accept
 *    `connRecv`, so it is provably live and holding a cache ref (inFlight > 0) at a known instant;
 *  - [JvmQuicServer.deRouteAllDriversForTest] then removes it from the routing table exactly as the
 *    production trySend-failure `removeIf` would, leaving a live-but-unroutable driver.
 *
 * With `close()` racing that state: the fix's driver-ledger sweep must join the frozen driver before
 * freeing the cache. On the pre-fix code this test trips the `check(inFlight == 0)` tripwire (or the
 * native UAF); on the fixed code it closes cleanly. Verified both directions locally.
 */
class ServerCloseRecvInfoRaceTest {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    @Test
    fun serverCloseReapsDeRoutedLiveDriverWithoutRecvInfoUseAfterFree() =
        runQuicTest(timeout = 40.seconds) {
            try {
                val opts = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false)
                val gated = GatedConnRecvQuicheApi(loadQuicheApi())
                val server = buildJvmQuicServer(port = 0, host = "127.0.0.1", tlsConfig = tls, quicOptions = opts, api = gated)
                val serverPort = server.port

                val established = CompletableDeferred<Unit>()
                val startWrites = CompletableDeferred<Unit>()
                val writesDone = CompletableDeferred<Unit>()
                val holdOpen = CompletableDeferred<Unit>()

                val clientJob =
                    launch {
                        try {
                            commonJvmWithQuicConnection(
                                hostname = "127.0.0.1",
                                port = serverPort,
                                quicOptions = opts,
                                connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
                                timeout = 15.seconds,
                            ) {
                                val stream = openStream()
                                established.complete(Unit)
                                startWrites.await()
                                // A short burst is enough to make the server run loop enter connRecv; the
                                // one-shot gate freezes the first, the rest buffer in the frozen driver.
                                repeat(3) {
                                    val b = BufferFactory.deterministic().allocate(4)
                                    b.writeString("ping", Charset.UTF8)
                                    b.resetForRead()
                                    try {
                                        stream.write(b, 5.seconds)
                                    } finally {
                                        b.freeNativeMemory()
                                    }
                                    delay(30)
                                }
                                writesDone.complete(Unit)
                                // Keep the connection open + idle so no stray packet arrives after the de-route.
                                holdOpen.await()
                            }
                        } catch (_: Throwable) {
                            // The connection is torn down by server.close() below — expected.
                        }
                    }

                established.await()
                gated.arm()
                startWrites.complete(Unit)
                assertTrue(
                    gated.awaitConnRecvBlocked(20_000),
                    "server never froze in a post-accept connRecv — cannot exercise the close/connRecv race",
                )
                // Client is now sending its burst; wait for it to stop and for in-flight packets to be
                // absorbed by the frozen (still-routed) driver, so the de-route leaves nothing in flight.
                writesDone.await()
                delay(200)

                // Live driver, frozen mid-connRecv, still holding a recv_info cache ref — now make it
                // unroutable exactly as the production trySend-failure removeIf does.
                server.deRouteAllDriversForTest()

                // Race close() against the frozen driver. Pre-fix: close() misses the de-routed driver and
                // trips check(inFlight==0) / UAFs the cache. Post-fix: it joins the driver via liveDrivers.
                val closeResult = async(Dispatchers.IO) { runCatching { server.close() } }
                delay(500) // let close() reach the driver sweep / cache free (its critical section)
                gated.releaseGate()
                val result = closeResult.await()
                holdOpen.complete(Unit)
                clientJob.cancel()

                assertTrue(
                    result.isSuccess,
                    "server.close() must reap the de-routed live driver and free the recv_info cache " +
                        "without a use-after-free, but failed with: ${result.exceptionOrNull()}",
                )
            } catch (e: UnsatisfiedLinkError) {
                assumeTrue("Native lib not available: ${e.message}", false)
            }
        }
}
