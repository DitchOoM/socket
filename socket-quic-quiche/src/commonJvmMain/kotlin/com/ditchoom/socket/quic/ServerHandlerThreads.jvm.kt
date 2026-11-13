package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

internal actual class ServerHandlerThreads actual constructor(
    serverName: String,
) {
    /**
     * The queue holds nothing: a resume goes to the thread that parked last, with one unpark, and when
     * none is parked the executor starts a thread for it instead of queueing it behind a handler that
     * may be blocked.
     */
    private val executor =
        ThreadPoolExecutor(
            keptThreads(),
            mostThreads(),
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            HandlerThreadFactory(serverName),
        )

    actual val dispatcher: CoroutineDispatcher = DeadlineTimer.Shared.over(HandlerDispatcher(executor))

    actual fun release() = executor.shutdown()

    internal companion object {
        /** The prefix of every handler thread's name. */
        const val THREAD_NAME_PREFIX = "quic-handler/"

        /** How long a thread beyond [keptThreads] waits for a resume before it exits. */
        private const val IDLE_SECONDS = 10L

        /**
         * Threads that stay parked between requests instead of exiting: half the cores, so handlers and
         * loops together do not outnumber them, at least two, at most eight.
         */
        fun keptThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)

        /** The most threads at once, equal to the default number `Dispatchers.IO` allows. */
        fun mostThreads(): Int = maxOf(64, Runtime.getRuntime().availableProcessors())
    }
}

/**
 * Runs a resume on [executor]. A resume it refuses, because [ServerHandlerThreads.mostThreads] handlers
 * are all running or because the server has released its threads, runs on `Dispatchers.IO`, so no
 * handler is left suspended and no dispatching thread waits.
 */
private class HandlerDispatcher(
    private val executor: ThreadPoolExecutor,
) : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            Dispatchers.IO.dispatch(context, block)
        }
    }

    override fun toString(): String = "quic-handler-threads"
}

private class HandlerThreadFactory(
    private val serverName: String,
) : ThreadFactory {
    private val started = AtomicInteger()

    override fun newThread(task: Runnable): Thread =
        Thread(task, "${ServerHandlerThreads.THREAD_NAME_PREFIX}$serverName-${started.incrementAndGet()}").apply {
            isDaemon = true
        }
}
