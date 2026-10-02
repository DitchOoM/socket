package com.ditchoom.socket.nio.util

import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.NetworkChannel
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel
import java.nio.channels.spi.AbstractSelectableChannel
import java.util.concurrent.TimeoutException
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
 */
suspend fun AbstractSelectableChannel.suspendUntilReady(
    selector: Selector,
    ops: Int,
    timeout: Duration,
) {
    val key =
        try {
            register(selector, ops)
        } catch (e: ClosedSelectorException) {
            throw SocketClosedException.General("Socket closed", e)
        } catch (e: CancelledKeyException) {
            throw SocketClosedException.General("Socket closed", e)
        }
    selector.awaitSelected(key, timeout)
    if (!key.isValid) throw SocketClosedException.General("Socket closed while waiting for it to be ready")
}

/**
 * Waits up to [timeout] for [selectionKey] itself to be selected, then resumes its continuation.
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
    awaitSelected(selectionKey, timeout)
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

/**
 * Polls this selector until [key] is among its selected keys, for at most [timeout]. Throws
 * [SocketTimeoutException] at the deadline, and [SocketClosedException] once the selector closes or
 * [key]'s channel does, since neither can ever select it.
 */
private suspend fun Selector.awaitSelected(
    key: SelectionKey,
    timeout: Duration,
) {
    withTimeoutOrNull(timeout) { pollUntilSelected(key) }
        ?: throw SocketTimeoutException("Selector timed out after waiting $timeout for ${key.interestOpsOrClosed()}")
}

private suspend fun Selector.pollUntilSelected(key: SelectionKey): SelectionKey =
    withContext(Dispatchers.IO.limitedParallelism(1)) {
        try {
            while (!selectedKeys().remove(key)) {
                ensureActive()
                if (!key.isValid) throw SocketClosedException.General("Socket closed while waiting for it to be ready")
                selectNow()
            }
            key
        } catch (closed: ClosedSelectorException) {
            throw SocketClosedException.General("Socket closed while waiting for it to be ready", closed)
        }
    }

private fun SelectionKey.interestOpsOrClosed(): String = if (isValid) "interest ops ${interestOps()}" else "a closed channel"

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

private suspend fun AbstractSelectableChannel.suspendNonBlockingSelector(
    selector: Selector?,
    op: Int,
    timeout: Duration,
) {
    if (isBlocking) {
        return
    }
    val selectorNonNull =
        selector
            ?: throw IllegalArgumentException("Selector must be provided if it is a non-blocking channel")
    suspendUntilReady(selectorNonNull, op, timeout)
}

suspend fun <T> T.read(
    buffer: ByteBuffer,
    selector: Selector?,
    timeout: Duration,
): Int where T : AbstractSelectableChannel, T : ReadableByteChannel =
    if (isBlocking) {
        withContext(Dispatchers.IO) {
            suspendRead(buffer)
        }
    } else {
        suspendNonBlockingSelector(selector, SelectionKey.OP_READ, timeout)
        suspendRead(buffer)
    }

suspend fun <T> T.write(
    buffer: ByteBuffer,
    selector: Selector?,
    timeout: Duration,
): Int where T : AbstractSelectableChannel, T : WritableByteChannel =
    if (isBlocking) {
        withContext(Dispatchers.IO) {
            suspendWrite(buffer)
        }
    } else {
        suspendNonBlockingSelector(selector, SelectionKey.OP_WRITE, timeout)
        suspendWrite(buffer)
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
