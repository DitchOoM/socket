package com.ditchoom.socket.nio2.util

import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketException
import kotlinx.coroutines.CancellableContinuation
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AsyncVoidIOHandler(
    private val cb: (() -> Unit)? = null,
) : CompletionHandler<Void?, CancellableContinuation<Unit>> {
    override fun completed(
        result: Void?,
        cont: CancellableContinuation<Unit>,
    ) {
        cb?.invoke()
        cont.resume(Unit)
    }

    override fun failed(
        ex: Throwable,
        cont: CancellableContinuation<Unit>,
    ) {
        // just return if already cancelled and got an expected exception for that case
        if (ex is AsynchronousCloseException && cont.isCancelled) return
        cont.resumeWithException(ex)
    }
}

internal object AsyncIOHandlerAny :
    CompletionHandler<Any, CancellableContinuation<Any>> {
    override fun completed(
        result: Any,
        cont: CancellableContinuation<Any>,
    ) {
        cont.resume(result)
    }

    override fun failed(
        ex: Throwable,
        cont: CancellableContinuation<Any>,
    ) {
        // just return if already cancelled and got an expected exception for that case
        if (ex is AsynchronousCloseException && cont.isCancelled) return
        cont.resumeWithException(ex)
    }
}

fun asyncIOIntHandler(): CompletionHandler<Int, CancellableContinuation<Int>> =
    object : CompletionHandler<Int, CancellableContinuation<Int>> {
        override fun completed(
            result: Int,
            attachment: CancellableContinuation<Int>,
        ) {
            attachment.resume(result)
        }

        override fun failed(
            ex: Throwable,
            cont: CancellableContinuation<Int>,
        ) {
            // Just return if already cancelled and got an expected exception for that case
            if (ex is AsynchronousCloseException && cont.isCancelled) return
            val message = "Socket operation failed."
            if (ex is AsynchronousCloseException) {
                cont.resumeWithException(SocketClosedException(message, ex))
            } else {
                cont.resumeWithException(SocketException(message, ex))
            }
        }
    }

@Suppress("UNCHECKED_CAST")
fun <T> asyncIOHandler(): CompletionHandler<T, CancellableContinuation<T>> =
    AsyncIOHandlerAny as CompletionHandler<T, CancellableContinuation<T>>
