package com.ditchoom.socket.nio.util

import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.NetworkChannel
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel
import java.nio.channels.spi.AbstractSelectableChannel
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

suspend fun openSocketChannel(remote: SocketAddress? = null) =
    suspendCoroutine<SocketChannel> {
        try {
            it.resume(
                if (remote == null) {
                    SocketChannel.open()
                } else {
                    SocketChannel.open(remote)
                },
            )
        } catch (e: Throwable) {
            it.resumeWithException(wrapJvmException(e))
        }
    }

data class WrappedContinuation<T>(
    val continuation: CancellableContinuation<T>,
    val attachment: T,
) {
    fun resume() = continuation.resume(attachment)

    fun cancel() = continuation.cancel()
}

fun SocketChannel.remoteAddressOrNull(): SocketAddress? =
    try {
        remoteAddress
    } catch (e: Exception) {
        null
    }

/**
 * Suspends until this channel is ready for [ops] on [selector], for at most [timeout]: a
 * [SocketTimeoutException] at the deadline, a [SocketClosedException] if the socket closes first.
 * Cancelling the caller ends the wait.
 *
 * The wait is parked on the selector's [SelectorLoop], its only owner: concurrent waits on one channel
 * (a read's OP_READ and a write's OP_WRITE) add to the channel's interest set rather than replacing
 * it, and each is woken by its own operation's readiness.
 */
suspend fun AbstractSelectableChannel.suspendUntilReady(
    selector: Selector,
    ops: Int,
    timeout: Duration,
) {
    awaitReady(selector, ops, timeout)
    if (!isOpen) throw SocketClosedException.General("Socket closed while waiting for it to be ready")
}

/**
 * Waits up to [timeout] for [selectionKey] itself to be ready for its interest, then resumes its continuation.
 *
 * Only this key ends the wait: another key on the same selector (a concurrent read's or write's, a
 * connect race's other candidate) being ready does not. The wait ends typed — [SocketTimeoutException]
 * at [timeout], [SocketClosedException] when the selector or the key's channel closes under it.
 */
suspend fun Selector.select(
    selectionKey: SelectionKey,
    attachment: Any,
    timeout: Duration,
) {
    val ops =
        try {
            selectionKey.interestOps()
        } catch (e: CancelledKeyException) {
            throw SocketClosedException.General("Socket closed while waiting for it to be ready", e)
        }
    selectionKey.channel().awaitReady(this, ops, timeout)
    val cont = selectionKey.attachment() as WrappedContinuation<*>
    if (cont.attachment != attachment) {
        throw IllegalStateException("Continuation attachment was mutated!")
    }
    if (selectionKey.isValid) {
        cont.resume()
    } else {
        throw SocketClosedException.General("Socket closed while waiting for it to be ready")
    }
}

private suspend fun SelectableChannel.awaitReady(
    selector: Selector,
    ops: Int,
    timeout: Duration,
) {
    SelectorLoop.of(selector).awaitReady(this, ops, timeout)
}

suspend fun SocketChannel.aConnect(
    remote: SocketAddress,
    timeout: Duration,
) = if (isBlocking) {
    val socket = socket()!!
    try {
        withContext(Dispatchers.IO) {
            socket.connect(remote, timeout.inWholeMilliseconds.toInt())
        }
    } catch (e: java.net.SocketTimeoutException) {
        throw SocketTimeoutException("Socket Connect timeout", cause = e)
    }
} else {
    suspendConnect(remote)
}

private suspend fun SocketChannel.suspendConnect(remote: SocketAddress) {
    suspendCancellableCoroutine<Boolean> {
        try {
            it.resume(connect(remote))
        } catch (e: Throwable) {
            it.resumeWithException(wrapJvmException(e))
        }
        closeOnCancel(it)
    }
}

suspend fun SocketChannel.connect(
    remote: SocketAddress,
    selector: Selector? = null,
    timeout: Duration,
): Boolean {
    withTimeout(timeout) {
        aConnect(remote, timeout)
        if (selector != null && !isBlocking) {
            suspendUntilReady(selector, SelectionKey.OP_CONNECT, timeout)
        }
    }
    if (aFinishConnecting()) {
        return true
    }
    throw TimeoutException("Failed to connect to $remote within $timeout maybe invalid selector")
}

suspend fun SocketChannel.aFinishConnecting() =
    withContext(Dispatchers.Default) {
        suspendCancellableCoroutine {
            try {
                while (it.isActive && !finishConnect()) {
                }
                it.resume(true)
            } catch (e: Throwable) {
                it.resumeWithException(wrapJvmException(e))
            }
        }
    }

suspend fun SelectableChannel.aConfigureBlocking(block: Boolean) =
    suspendCoroutine<SelectableChannel> {
        try {
            it.resume(configureBlocking(block))
        } catch (e: Throwable) {
            it.resumeWithException(wrapJvmException(e))
        }
    }

private suspend fun AbstractSelectableChannel.awaitReadiness(
    loop: SelectorLoop,
    op: Int,
    timeout: Duration,
) {
    loop.awaitReady(this, op, timeout)
    if (!isOpen) throw SocketClosedException.General("Socket closed while waiting for it to be ready")
}

/** The selector a non-blocking channel's waits run on is the caller's to name; a blocking channel needs none. */
private fun Selector?.loopOrRefuse(): SelectorLoop =
    SelectorLoop.of(this ?: throw IllegalArgumentException("Selector must be provided if it is a non-blocking channel"))

suspend fun <T> T.read(
    buffer: ByteBuffer,
    selector: Selector?,
    timeout: Duration,
): Int where T : AbstractSelectableChannel, T : ReadableByteChannel =
    if (isBlocking) readBlocking(buffer) else read(buffer, selector.loopOrRefuse(), timeout, ReadPacing())

internal suspend fun <T> T.readBlocking(buffer: ByteBuffer): Int where T : AbstractSelectableChannel, T : ReadableByteChannel =
    withContext(Dispatchers.IO) { suspendRead(buffer) }

/**
 * Whether the next read on a connection registers for readiness before it reads. A request/response
 * connection finds nothing queued almost every time it reads, so a speculative read is a wasted system call;
 * a streaming one finds data every time, so registering first is a wasted wake-up. A connection that had to
 * wait registers first next time, until a read fills the whole buffer.
 */
internal enum class ReadStep { ReadFirst, RegisterFirst }

/** The [ReadStep] of one connection. */
internal class ReadPacing {
    private val step = AtomicReference(ReadStep.ReadFirst)

    fun next(): ReadStep = step.get()

    fun learn(
        waited: Boolean,
        filledBuffer: Boolean,
    ) = step.set(if (waited && !filledBuffer) ReadStep.RegisterFirst else ReadStep.ReadFirst)
}

internal suspend fun <T> T.read(
    buffer: ByteBuffer,
    loop: SelectorLoop,
    timeout: Duration,
    pacing: ReadPacing,
): Int where T : AbstractSelectableChannel, T : ReadableByteChannel {
    val capacity = buffer.remaining()
    var waited = pacing.next() == ReadStep.RegisterFirst
    if (waited) awaitReadiness(loop, SelectionKey.OP_READ, timeout)
    while (true) {
        val read = read(buffer)
        if (read != 0 || capacity == 0) {
            pacing.learn(waited, filledBuffer = read >= capacity)
            return read
        }
        waited = true
        awaitReadiness(loop, SelectionKey.OP_READ, timeout)
    }
}

suspend fun <T> T.write(
    buffer: ByteBuffer,
    selector: Selector?,
    timeout: Duration,
): Int where T : AbstractSelectableChannel, T : WritableByteChannel =
    if (isBlocking) writeBlocking(buffer) else write(buffer, selector.loopOrRefuse(), timeout)

internal suspend fun <T> T.writeBlocking(buffer: ByteBuffer): Int where T : AbstractSelectableChannel, T : WritableByteChannel =
    withContext(Dispatchers.IO) { suspendWrite(buffer) }

internal suspend fun <T> T.write(
    buffer: ByteBuffer,
    loop: SelectorLoop,
    timeout: Duration,
): Int where T : AbstractSelectableChannel, T : WritableByteChannel {
    // Write first: a send buffer with room takes the bytes without registering OP_WRITE at all.
    while (true) {
        val written = write(buffer)
        if (written != 0 || !buffer.hasRemaining()) return written
        awaitReadiness(loop, SelectionKey.OP_WRITE, timeout)
    }
}

fun NetworkChannel.closeOnCancel(cont: CancellableContinuation<*>) {
    cont.invokeOnCancellation {
        blockingClose()
    }
}

private suspend fun ReadableByteChannel.suspendRead(buffer: ByteBuffer) =
    suspendCancellableCoroutine<Int> {
        try {
            val read = read(buffer)
            it.resume(read)
        } catch (ex: Throwable) {
            if (this is NetworkChannel) {
                closeOnCancel(it)
            }
            // Resume with the original exception if continuation is still active.
            // Don't wrap here — callers (BaseClientSocket.read) catch ClosedChannelException
            // and wrap to SocketClosedException themselves.
            if (it.isActive) {
                it.resumeWithException(ex)
            }
        }
    }

private suspend fun WritableByteChannel.suspendWrite(buffer: ByteBuffer) =
    suspendCancellableCoroutine<Int> {
        try {
            val wrote = write(buffer)
            it.resume(wrote)
        } catch (ex: Throwable) {
            if (this is NetworkChannel) {
                closeOnCancel(it)
            }
            if (it.isActive) {
                it.resumeWithException(ex)
            }
        }
    }
