package com.ditchoom.socket.nio2.util

import com.ditchoom.socket.nio.util.aLocalAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration


suspend fun asyncSocket(group: AsynchronousChannelGroup? = null) = suspendCoroutine<AsynchronousSocketChannel> {
    try {
        it.resume(AsynchronousSocketChannel.open(group))
    } catch (e: Throwable) {
        it.resumeWithException(e)
    }
}


/**
 * Performs [AsynchronousSocketChannel.connect] without blocking a thread and resumes when asynchronous operation completes.
 * This suspending function is cancellable.
 * If the [Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * *closes the underlying channel* and immediately resumes with [CancellationException].
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
 * If the [Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * *closes the underlying channel* and immediately resumes with [CancellationException].
 */

suspend fun AsynchronousSocketChannel.aRead(
    buf: ByteBuffer,
    duration: Duration
): Int {
    val result = suspendCancellableCoroutine<Int> { cont ->
        read(
            buf, duration.inWholeMilliseconds, TimeUnit.MILLISECONDS, cont,
            asyncIOIntHandler()
        )
        closeOnCancel(cont)
    }
    (buf as Buffer).flip()
    return result
}

/**
 * Performs [AsynchronousSocketChannel.write] without blocking a thread and resumes when asynchronous operation completes.
 * This suspending function is cancellable.
 * If the [Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * *closes the underlying channel* and immediately resumes with [CancellationException].
 */

suspend fun AsynchronousSocketChannel.aWrite(
    buf: ByteBuffer,
    duration: Duration
): Int {
    return suspendCancellableCoroutine<Int> { cont ->
        (buf as Buffer).flip()
        write(
            buf, duration.inWholeMilliseconds, TimeUnit.MILLISECONDS, cont,
            asyncIOHandler()
        )
        closeOnCancel(cont)
    }
}

/**
 * Performs [AsynchronousSocketChannel.close] without blocking a thread and resumes when asynchronous operation completes.
 * If the [Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * *closes the underlying channel* and immediately resumes with [CancellationException].
 */

suspend fun AsynchronousSocketChannel.aClose() {
    suspendCoroutine<Unit> { cont ->
        blockingClose()
        cont.resume(Unit)
    }
}

suspend fun AsynchronousSocketChannel.aRemoteAddress(): SocketAddress? = withContext(Dispatchers.IO) {
    remoteAddress
}

suspend fun AsynchronousSocketChannel.assignedPort(remote: Boolean = true): UShort? {
    return try {
        if (remote) {
            (aRemoteAddress() as? InetSocketAddress)?.port?.toUShort()
        } else {
            (aLocalAddress() as? InetSocketAddress)?.port?.toUShort()
        }
    } catch (e: Exception) {
        null
    }
}
