package com.ditchoom.socket.nio2.util

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AsyncVoidIOHandler(private val cb: (() -> Unit)? = null) :
    CompletionHandler<Void?, CancellableContinuation<Unit>> {

    override fun completed(result: Void?, cont: CancellableContinuation<Unit>) {
        cb?.invoke()
        cont.resume(Unit)
    }

    override fun failed(ex: Throwable, cont: CancellableContinuation<Unit>) {
        // just return if already cancelled and got an expected exception for that case
        if (ex is AsynchronousCloseException && cont.isCancelled) return
        cont.resumeWithException(ex)
    }

}

internal object AsyncIOHandlerAny :
    CompletionHandler<Any, CancellableContinuation<Any>> {
    override fun completed(result: Any, cont: CancellableContinuation<Any>) {
        cont.resume(result)
    }

    override fun failed(ex: Throwable, cont: CancellableContinuation<Any>) {
        // just return if already cancelled and got an expected exception for that case
        if (ex is AsynchronousCloseException && cont.isCancelled) return
        cont.resumeWithException(ex)
    }
}

fun asyncIOIntHandler(): CompletionHandler<Int, CancellableContinuation<Int>> =
    object : CompletionHandler<Int, CancellableContinuation<Int>> {
        override fun completed(result: Int, attachment: CancellableContinuation<Int>) {
            if (result == -1) {
                attachment.resumeWithException(ClosedReceiveChannelException("remote returned -1 bytes"))
                return
            }
            attachment.resume(result)
        }

        override fun failed(ex: Throwable, cont: CancellableContinuation<Int>) {
            cont.resumeWithException(ex)
        }

    }

@Suppress("UNCHECKED_CAST")
fun <T> asyncIOHandler(): CompletionHandler<T, CancellableContinuation<T>> =
    AsyncIOHandlerAny as CompletionHandler<T, CancellableContinuation<T>>