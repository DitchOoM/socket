package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Where a typed stream's close falls against the peer's answer to its FIN. */
class TypedCloseOrderTests {
    private val loops = LoopSet(2)
    private val closer = Executors.newSingleThreadExecutor()

    @AfterTest
    fun closeAll() {
        closer.shutdownNow()
        loops.close()
    }

    /**
     * The closing caller is held after its FIN left until the peer's own FIN has had time to arrive: the parked read
     * still fails, because the close failed it before the FIN went out.
     */
    @Test
    fun aReadParkedWhenTheStreamClosesFailsEvenIfThePeersFinArrivesBeforeTheCloseResumes() =
        coreTest("TypedCloseOrderTests.aReadParkedWhenTheStreamClosesFailsEvenIfThePeersFinArrivesBeforeTheCloseResumes") {
            val serverClosed = CompletableDeferred<Unit>()
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptTypedStream(NoteCodec)
                while (stream.receive() is ScopedReadResult.Data) Unit
                stream.close()
                serverClosed.complete(Unit)
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                stream.send(Note(1, "hello"))
                val parked = async { runCatching { stream.receive() } }
                delay(20.milliseconds)
                val held = CountDownLatch(1)
                val closing = launch(closer.asCoroutineDispatcher()) { stream.close() }
                // Whatever close() resumes with after its FIN waits behind this task.
                closer.execute { held.await() }
                withTimeoutOrNull(5.seconds) { serverClosed.await() }
                // The server's FIN reaches the client over loopback well within this.
                withTimeoutOrNull(500.milliseconds) { parked.await() }
                held.countDown()
                closing.join()
                val read = parked.await()
                assertTrue(read.isFailure, "the parked read must fail when the stream closes, got $read")
                client.connection.close(QuicError.NoError)
            }
        }
}
