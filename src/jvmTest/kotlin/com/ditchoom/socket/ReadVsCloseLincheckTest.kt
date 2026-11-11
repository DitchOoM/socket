package com.ditchoom.socket

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A JVM socket's `read()` racing this side's `close()`, checked by Lincheck in **stress** mode against
 * the read contract #797 fixed:
 *
 *  - a read returns Data the peer really sent, returns End only for the peer's graceful close, or
 *    fails with [SocketClosedException.General] because this side closed;
 *  - it never ends with a [CancellationException] (before #797 a close under a parked read handed the
 *    caller the background read's `JobCancellationException`, which ends the caller's coroutine as if
 *    *it* had been cancelled);
 *  - it never hangs: every read is bounded by [READ_DEADLINE], and a deadline is not an outcome the
 *    specification allows.
 *
 * ## Why stress mode, on real sockets
 *
 * Model checking needs every thread that touches the state under test to be one Lincheck schedules.
 * Here the read's completion arrives on the JDK's `AsynchronousChannelGroup` threads (NIO2) or a
 * `Dispatchers.IO` worker blocked in `SocketChannel.read` (NIO), and `close()` ends it through the
 * kernel — none of which Lincheck can pause or reorder. The alternative, extracting the orphaned-read
 * single-flight behind a seam with a fake channel, would be a production refactor of both
 * `AsyncBaseClientSocket` and `BaseClientSocket`; the pilot measures what Lincheck buys without one.
 * Stress mode runs the same operations on real threads many times and checks every outcome against
 * the sequential specification, which is what a hand-written race loop would do — with the
 * specification and the report written for us.
 *
 * ## The specification
 *
 * [ReadVsCloseSpec]: the bytes the peer sent before the race are readable until this side closes;
 * after the peer's FIN a read is End; after this side's close a read is Closed. A read with nothing to
 * return would block — until this side's close, which every scenario here performs — so in the
 * specification it is Closed.
 */
abstract class ReadVsCloseModel(
    newClient: () -> ClientToServerSocket,
    peerSends: String?,
    peerFin: Boolean,
) {
    private val client: ClientToServerSocket = newClient()
    private val peer: Socket

    init {
        // Lincheck has no per-invocation teardown, so each invocation closes the one before it.
        ReadVsCloseRig.closePrevious()
        runBlocking { client.open(ReadVsCloseRig.server.localPort, LOOPBACK) }
        peer = ReadVsCloseRig.server.accept()
        ReadVsCloseRig.track(client, peer)
        if (peerSends != null) {
            peer.getOutputStream().apply {
                write(peerSends.encodeToByteArray())
                flush()
            }
        }
        if (peerFin) peer.shutdownOutput()
    }

    @Operation
    fun read(): String =
        runBlocking {
            try {
                when (val result = client.read(READ_DEADLINE)) {
                    is ReadResult.Data -> "Data:" + result.buffer.readString(result.buffer.remaining())
                    ReadResult.End -> "End"
                    ReadResult.Reset -> "Reset"
                }
            } catch (e: SocketTimeoutException) {
                "Hung"
            } catch (e: SocketClosedException.General) {
                "Closed"
            } catch (e: CancellationException) {
                "LEAKED ${e::class.simpleName}"
            } catch (e: Throwable) {
                "${e::class.simpleName}: ${e.message}"
            }
        }

    @Operation
    fun close() = runBlocking { client.close() }

    /** Gives a read a head start, so the close more often lands on a read already parked in the platform. */
    @Operation
    fun settle() = Thread.sleep(1)

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private val READ_DEADLINE = 5.seconds
    }
}

/** The peer's side of every connection, and the connections still to close. */
internal object ReadVsCloseRig {
    val server: ServerSocket by lazy { ServerSocket(0, 64, InetAddress.getLoopbackAddress()) }
    private var open: Pair<ClientSocket, Socket>? = null

    fun track(
        client: ClientSocket,
        peer: Socket,
    ) {
        open = client to peer
    }

    fun closePrevious() {
        val (client, peer) = open ?: return
        open = null
        runCatching { runBlocking { client.close() } }
        runCatching { peer.close() }
    }
}

open class ReadVsCloseSpec(
    peerSends: String?,
    private val peerFin: Boolean,
) {
    private var pending: String? = peerSends
    private var closed = false

    fun read(): String {
        if (closed) return "Closed"
        pending?.let {
            pending = null
            return "Data:$it"
        }
        return if (peerFin) "End" else "Closed"
    }

    fun close() {
        closed = true
    }

    fun settle() {}
}

class SilentPeerSpec : ReadVsCloseSpec(peerSends = null, peerFin = false)

class PeerSentSpec : ReadVsCloseSpec(peerSends = PAYLOAD, peerFin = false)

class PeerFinishedSpec : ReadVsCloseSpec(peerSends = PAYLOAD, peerFin = true)

private const val PAYLOAD = "ping"

/**
 * One read racing one close, with and without a head start for the read, plus a second read for the
 * peers that have more to say. Custom scenarios only: these are the races the contract is about, and
 * generated ones would mostly read a silent socket nobody closes.
 */
private fun readVsCloseOptions(
    spec: Class<*>,
    secondRead: Boolean,
): StressOptions =
    StressOptions()
        .sequentialSpecification(spec)
        .addCustomScenario {
            parallel {
                thread { actor(ReadVsCloseModel::read) }
                thread { actor(ReadVsCloseModel::close) }
            }
        }.addCustomScenario {
            parallel {
                thread {
                    actor(ReadVsCloseModel::read)
                    if (secondRead) actor(ReadVsCloseModel::read)
                }
                thread {
                    actor(ReadVsCloseModel::settle)
                    actor(ReadVsCloseModel::close)
                }
            }
        }.iterations(0)
        .invocationsPerIteration(INVOCATIONS)

private const val INVOCATIONS = 300

private fun nio2() = AsyncClientSocket()

private fun nioBlocking() = NioClientSocket(blocking = true)

private fun nioSelector() = NioClientSocket(blocking = false)

class Nio2SilentPeerReadVsCloseLincheckTest : ReadVsCloseModel(::nio2, null, false) {
    @Test
    fun aCloseUnderAParkedReadFailsTheReadClosed() = readVsCloseOptions(SilentPeerSpec::class.java, secondRead = false).check(this::class)

    @AfterTest
    fun closeLast() = ReadVsCloseRig.closePrevious()
}

class Nio2PeerSentReadVsCloseLincheckTest : ReadVsCloseModel(::nio2, PAYLOAD, false) {
    @Test
    fun aReadRacingACloseReturnsTheBytesOrFailsClosed() = readVsCloseOptions(PeerSentSpec::class.java, secondRead = true).check(this::class)

    @AfterTest
    fun closeLast() = ReadVsCloseRig.closePrevious()
}

class Nio2PeerFinishedReadVsCloseLincheckTest : ReadVsCloseModel(::nio2, PAYLOAD, true) {
    @Test
    fun aReadRacingACloseEndsOnlyForThePeersFin() = readVsCloseOptions(PeerFinishedSpec::class.java, secondRead = true).check(this::class)

    @AfterTest
    fun closeLast() = ReadVsCloseRig.closePrevious()
}

class NioBlockingSilentPeerReadVsCloseLincheckTest : ReadVsCloseModel(::nioBlocking, null, false) {
    @Test
    fun aCloseUnderAParkedReadFailsTheReadClosed() = readVsCloseOptions(SilentPeerSpec::class.java, secondRead = false).check(this::class)

    @AfterTest
    fun closeLast() = ReadVsCloseRig.closePrevious()
}

class NioBlockingPeerFinishedReadVsCloseLincheckTest : ReadVsCloseModel(::nioBlocking, PAYLOAD, true) {
    @Test
    fun aReadRacingACloseEndsOnlyForThePeersFin() = readVsCloseOptions(PeerFinishedSpec::class.java, secondRead = true).check(this::class)

    @AfterTest
    fun closeLast() = ReadVsCloseRig.closePrevious()
}

class NioSelectorSilentPeerReadVsCloseLincheckTest : ReadVsCloseModel(::nioSelector, null, false) {
    @Test
    fun aCloseUnderAParkedReadFailsTheReadClosed() = readVsCloseOptions(SilentPeerSpec::class.java, secondRead = false).check(this::class)

    @AfterTest
    fun closeLast() = ReadVsCloseRig.closePrevious()
}
