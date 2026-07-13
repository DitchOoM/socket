package com.ditchoom.socket.nio2.util

import com.ditchoom.socket.nio.util.localAddressOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

suspend fun asyncSocket(group: AsynchronousChannelGroup? = null) =
    suspendCoroutine<AsynchronousSocketChannel> {
        try {
            it.resume(AsynchronousSocketChannel.open(group))
        } catch (e: Throwable) {
            it.resumeWithException(com.ditchoom.socket.wrapJvmException(e))
        }
    }

/**
 * Performs [AsynchronousSocketChannel.connect] without blocking a thread and resumes when asynchronous operation completes.
 * This suspending function is cancellable.
 * If the [kotlinx.coroutines.Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * *closes the underlying channel* and immediately resumes with [kotlin.coroutines.cancellation.CancellationException].
 */
suspend fun AsynchronousSocketChannel.aConnect(
    socketAddress: SocketAddress,
    timeout: Duration,
) = withTimeout(timeout) {
    suspendCancellableCoroutine<Unit> { cont ->
        connect(socketAddress, cont, AsyncVoidIOHandler())
        closeOnCancel(cont)
    }
}

/**
 * Performs [AsynchronousSocketChannel.read] without blocking a thread and resumes when asynchronous operation completes.
 * This suspending function is cancellable.
 * If the [kotlinx.coroutines.Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * *closes the underlying channel* and immediately resumes with [kotlin.coroutines.cancellation.CancellationException].
 *
 * An **infinite** [duration] maps to the JDK's *untimed* `read` overload rather than a very large
 * millisecond timeout. This is deliberate and load-bearing: the JDK read-timeout is **destructive**
 * — when it elapses the channel's `readKilled` flag is armed and every later read throws
 * `IllegalStateException` (RFC_READ_TIMEOUT_CONTRACT §4, Axis 2). The non-destructive read path
 * ([com.ditchoom.socket.nio2.AsyncBaseClientSocket] orphaned-read single-flight) therefore enforces
 * the deadline itself and calls here with [Duration.INFINITE], relying on the untimed read to never
 * arm `readKilled`. Passing a finite [duration] keeps the JDK's destructive timeout (used only on the
 * TLS raw-read path, which is out of scope for the contract — see the class doc).
 */

suspend fun AsynchronousSocketChannel.aRead(
    buf: ByteBuffer,
    duration: Duration,
): Int {
    val result =
        suspendCancellableCoroutine<Int> { cont ->
            if (duration.isInfinite()) {
                read(buf, cont, asyncIOIntHandler())
            } else {
                read(
                    buf,
                    duration.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                    cont,
                    asyncIOIntHandler(),
                )
            }
            closeOnCancel(cont)
        }
    return result
}

/**
 * Performs [AsynchronousSocketChannel.write] without blocking a thread and resumes when asynchronous operation completes.
 * This suspending function is cancellable.
 * If the [kotlinx.coroutines.Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * *closes the underlying channel* and immediately resumes with [kotlin.coroutines.cancellation.CancellationException].
 *
 * An **infinite** [duration] maps to the JDK's *untimed* `write` overload rather than a `Long.MAX_VALUE`
 * millisecond timeout — the write-side analogue of [aRead]. The default write policy is
 * `WritePolicy.UntilClosed` (RFC_WRITE_TIMEOUT_CONTRACT §2): a stalled write must **suspend** on
 * back-pressure, and the untimed overload never arms the JDK's `writeKilled` timeout machinery. A finite
 * [duration] keeps the timed overload — an opt-in `Bounded(d)` write, whose timeout is deliberately
 * destructive (the caller's `write` closes the connection when it elapses).
 */

suspend fun AsynchronousSocketChannel.aWrite(
    buf: ByteBuffer,
    duration: Duration,
): Int =
    suspendCancellableCoroutine<Int> { cont ->
        if (duration.isInfinite()) {
            write(buf, cont, asyncIOHandler())
        } else {
            write(
                buf,
                duration.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
                cont,
                asyncIOHandler(),
            )
        }
        closeOnCancel(cont)
    }

/**
 * Performs [AsynchronousSocketChannel.close] without blocking a thread and resumes when asynchronous operation completes.
 * If the [kotlinx.coroutines.Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * *closes the underlying channel* and immediately resumes with [kotlin.coroutines.cancellation.CancellationException].
 */

suspend fun AsynchronousSocketChannel.aClose() {
    suspendCoroutine<Unit> { cont ->
        blockingClose()
        cont.resume(Unit)
    }
}

suspend fun AsynchronousSocketChannel.aRemoteAddress(): SocketAddress? =
    withContext(Dispatchers.IO) {
        remoteAddress
    }

suspend fun AsynchronousSocketChannel.assignedPort(remote: Boolean = true): Int =
    try {
        if (remote) {
            (aRemoteAddress() as? InetSocketAddress)?.port ?: -1
        } else {
            (localAddressOrNull() as? InetSocketAddress)?.port ?: -1
        }
    } catch (e: Exception) {
        -1
    }
