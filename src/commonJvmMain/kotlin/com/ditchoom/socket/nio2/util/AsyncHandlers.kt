package com.ditchoom.socket.nio2.util

import com.ditchoom.socket.wrapJvmException
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
        // AsynchronousCloseException during cancellation is expected — the closeOnCancel
        // handler closed the channel. Don't wrap it; let the cancellation propagate.
        if (ex is AsynchronousCloseException && !cont.isActive) return
        cont.resumeWithException(wrapJvmException(ex))
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
        if (ex is AsynchronousCloseException && !cont.isActive) return
        cont.resumeWithException(wrapJvmException(ex))
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
            if (ex is AsynchronousCloseException && !cont.isActive) return
            cont.resumeWithException(wrapJvmException(ex))
        }
    }

@Suppress("UNCHECKED_CAST")
fun <T> asyncIOHandler(): CompletionHandler<T, CancellableContinuation<T>> =
    AsyncIOHandlerAny as CompletionHandler<T, CancellableContinuation<T>>
