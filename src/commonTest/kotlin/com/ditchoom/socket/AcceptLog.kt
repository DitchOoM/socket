package com.ditchoom.socket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Which connections a test's server accepted, from which remote port and when, next to what the test's
 * own client did — so a server-side failure can say whether the peer it failed on was this test's client.
 *
 * Why: `DataIntegrityTests.nullBytesInData` once failed on the iOS simulator because the server read
 * end-of-stream within 6 ms, with four suites running on the host. The host's network stack is shared
 * by everything on it, the simulator included, and some tests elsewhere connect to a port they believe
 * is closed and then close at once (see [REFUSED_PORT]). When that port is this server's, the server
 * accepts the stranger first and reads its end-of-stream — the same 6 ms signature. Nothing recorded
 * which peer the server had accepted, so the run could not tell the two apart.
 *
 * Cheap on the passing path: a port lookup and one short timestamped line per accepted connection and
 * per client step, a handful per test, none of it on the data path. The remote port is read at accept
 * time because it is gone once the connection ends (an Apple socket reports -1 after end-of-stream).
 * The verdict, which may wait for the client to register, is composed only when a handler throws.
 */
internal class AcceptLog {
    private val origin = TimeSource.Monotonic.markNow()
    private val lock = Mutex()
    private val lines = mutableListOf<String>()
    private val clientPort = CompletableDeferred<Int>()
    private var accepted = 0

    private fun at() = "+${origin.elapsedNow()}"

    /** Registers this test's own client, after its `open()`. */
    suspend fun client(socket: ClientSocket) {
        val port = socket.localPort()
        lock.withLock { lines += "${at()} this test's client connected from local port $port to ${socket.remotePort()}; $socket" }
        clientPort.complete(port)
    }

    /** A step of this test's client, e.g. "wrote 10 bytes", in the same timeline. */
    suspend fun note(what: String) {
        lock.withLock { lines += "${at()} $what" }
    }

    /** Runs [handle] on an accepted [socket]; if it throws, rethrows with this log as the message. */
    suspend fun <R> serve(
        socket: ClientSocket,
        handle: suspend () -> R,
    ): R {
        val remote = socket.remotePort()
        val number =
            lock.withLock {
                (++accepted).also {
                    lines +=
                        "${at()} server accepted #$it from remote port $remote on ${socket.localPort()}; $socket"
                }
            }
        try {
            return handle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lock.withLock { lines += "${at()} server handler for #$number threw ${e::class.simpleName}: ${e.message}" }
            throw AssertionError(describe(number, remote), e)
        }
    }

    /** The log so far, for an assertion message on the client side. */
    suspend fun describe(): String = lock.withLock { lines.joinToString("\n  ", prefix = "accept log:\n  ") }

    private suspend fun describe(
        failed: Int,
        remote: Int,
    ): String {
        // Failure path only: the client may register a moment after the server already failed.
        val ours = withTimeoutOrNull(2.seconds) { clientPort.await() }
        val verdict =
            when {
                ours == null -> "this test's client never registered, so whose connection #$failed was is unknown"
                ours == remote -> "#$failed IS this test's client (remote port $remote)"
                else ->
                    "#$failed is NOT this test's client (remote port $remote, ours is $ours): another socket on this host " +
                        "connected to this server's port"
            }
        return "server handler failed on accepted connection #$failed — $verdict\n  ${describe()}"
    }
}

/** [serveEach], with every accepted socket and any handler failure recorded in [log]. */
internal suspend fun Flow<ClientSocket>.serveEach(
    log: AcceptLog,
    handle: suspend (ClientSocket) -> Unit,
) = serveEach { client -> log.serve(client) { handle(client) } }
