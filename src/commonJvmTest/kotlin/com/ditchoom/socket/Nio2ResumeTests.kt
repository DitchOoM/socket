package com.ditchoom.socket

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.data.writeString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * On the default NIO2 backend a read's completion runs on the JDK's own pool thread. That thread must never
 * run a caller's code: a caller with no dispatcher to leave through (Unconfined, a dispatcher that never
 * dispatches) is resumed on `Dispatchers.Default`, not on the JDK thread that completed the read.
 */
class Nio2ResumeTests {
    private fun withPair(block: suspend CoroutineScope.(server: ClientSocket, client: ClientSocket) -> Unit) =
        runBlocking(Dispatchers.IO) {
            withTimeout(30.seconds) {
                val listener = ServerSocket.allocate()
                val accepted = CompletableDeferred<ClientSocket>()
                val flow = listener.bind(0, "127.0.0.1")
                val acceptor = launch { flow.collect { accepted.complete(it) } }
                val client = ClientSocket.connect(listener.port(), "127.0.0.1")
                val server = accepted.await()
                try {
                    block(server, client)
                } finally {
                    client.close()
                    server.close()
                    listener.close()
                    acceptor.cancel()
                }
            }
        }

    private suspend fun CoroutineScope.resumedOnWhenReadUnder(
        context: CoroutineContext,
        server: ClientSocket,
        client: ClientSocket,
    ): String {
        val resumedOn = CompletableDeferred<String>()
        val reader =
            launch(context) {
                val result = client.read(10.seconds)
                (result as? ReadResult.Data)?.buffer?.freeIfNeeded()
                resumedOn.complete(Thread.currentThread().name)
            }
        delay(200)
        server.writeString("x")
        val thread = withTimeout(5.seconds) { resumedOn.await() }
        reader.join()
        return thread
    }

    @Test
    fun aCallerWithNoDispatcherIsNotResumedOnTheJdkCompletionThread() =
        withPair { server, client ->
            val thread = resumedOnWhenReadUnder(Dispatchers.Unconfined, server, client)
            assertTrue(thread.startsWith("DefaultDispatcher-worker"), "the caller's code ran on $thread")
        }

    @Test
    fun aDispatcherThatNeverDispatchesIsNotResumedOnTheJdkCompletionThread() =
        withPair { server, client ->
            val thread = resumedOnWhenReadUnder(NeverDispatches, server, client)
            assertTrue(thread.startsWith("DefaultDispatcher-worker"), "the caller's code ran on $thread")
        }

    private object NeverDispatches : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = false

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = block.run()
    }
}
