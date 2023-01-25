package com.ditchoom.socket.nio.util

import com.ditchoom.socket.SocketException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.Math.random
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
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
import kotlin.time.Duration.Companion.milliseconds

suspend fun openSocketChannel(remote: SocketAddress? = null) = suspendCoroutine<SocketChannel> {
    try {
        it.resume(
            if (remote == null) {
                SocketChannel.open()
            } else {
                SocketChannel.open(remote)
            }
        )
    } catch (e: Throwable) {
        it.resumeWithException(e)
    }
}

data class WrappedContinuation<T>(val continuation: CancellableContinuation<T>, val attachment: T) {
    fun resume() = continuation.resume(attachment)
    fun cancel() = continuation.cancel()
}

suspend fun SocketChannel.aRemoteAddress(): SocketAddress? = withContext(Dispatchers.IO) {
    remoteAddress
}

suspend fun AbstractSelectableChannel.suspendUntilReady(
    selector: Selector,
    ops: Int,
    timeout: Duration
) {
    val random = random()
    suspendCancellableCoroutine<Double> {
        val key = register(selector, ops, WrappedContinuation(it, random))
        runBlocking {
            selector.select(key, random, timeout)
        }
    }
}

suspend fun Selector.select(selectionKey: SelectionKey, attachment: Any, timeout: Duration) {
    val startTime = System.currentTimeMillis()
    val selectedCount = aSelect(timeout)
    if (selectedCount == 0) {
        throw SocketException("Selector timed out after waiting $timeout for ${selectionKey.isConnectable}")
    }
    while (isOpen && timeout - (System.currentTimeMillis() - startTime).milliseconds > 0.milliseconds) {
        if (selectedKeys().remove(selectionKey)) {
            val cont = selectionKey.attachment() as WrappedContinuation<*>
            if (cont.attachment != attachment) {
                throw IllegalStateException("Continuation attachment was mutated!")
            }
            if (selectionKey.isValid) {
                cont.resume()
            } else {
                cont.cancel()
            }
            return
        }
    }

    throw CancellationException("Failed to find selector in time")
}

suspend fun SocketChannel.aConnect(remote: SocketAddress, timeout: Duration) = if (isBlocking) {
    val socket = socket()!!
    try {
        withContext(Dispatchers.IO) {
            socket.connect(remote, timeout.inWholeMilliseconds.toInt())
        }
    } catch (e: SocketTimeoutException) {
        throw SocketException("Socket Connect timeout", e)
    }
} else {
    suspendConnect(remote)
}

private suspend fun SocketChannel.suspendConnect(remote: SocketAddress) {
    suspendCancellableCoroutine<Boolean> {
        try {
            it.resume(connect(remote))
        } catch (e: Throwable) {
            it.resumeWithException(e)
        }
        closeOnCancel(it)
    }
}

suspend fun SocketChannel.connect(
    remote: SocketAddress,
    selector: Selector? = null,
    timeout: Duration
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

suspend fun SocketChannel.aFinishConnecting() = withContext(Dispatchers.Default) {
    suspendCancellableCoroutine {
        try {
            while (it.isActive && !finishConnect()) {
            }
            it.resume(true)
        } catch (e: Throwable) {
            it.resumeWithException(e)
        }
    }
}

suspend fun SelectableChannel.aConfigureBlocking(block: Boolean) =
    suspendCoroutine<SelectableChannel> {
        try {
            it.resume(configureBlocking(block))
        } catch (e: Throwable) {
            it.resumeWithException(e)
        }
    }

private suspend fun AbstractSelectableChannel.suspendNonBlockingSelector(
    selector: Selector?,
    op: Int,
    timeout: Duration
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
    timeout: Duration
): Int where T : AbstractSelectableChannel, T : ReadableByteChannel {
    return if (isBlocking) {
        withContext(Dispatchers.IO) {
            suspendRead(buffer)
        }
    } else {
        suspendNonBlockingSelector(selector, SelectionKey.OP_READ, timeout)
        suspendRead(buffer)
    }
}

suspend fun <T> T.write(
    buffer: ByteBuffer,
    selector: Selector?,
    timeout: Duration
): Int where T : AbstractSelectableChannel, T : WritableByteChannel {
    return if (isBlocking) {
        withContext(Dispatchers.IO) {
            suspendWrite(buffer)
        }
    } else {
        suspendNonBlockingSelector(selector, SelectionKey.OP_WRITE, timeout)
        suspendWrite(buffer)
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
        }
    }
