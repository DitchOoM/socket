package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import reactor.blockhound.BlockHound
import reactor.blockhound.BlockingOperationError
import reactor.blockhound.integration.BlockHoundIntegration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A caller on a main dispatcher connects, writes 64 KiB and reads it back without one blocking call on its thread,
 * and without one send or receive on the socket from it: the loop does every system call for it. Runs under
 * BlockHound, which needs its own JVM (`mainThreadSafetyTest`) and `-XX:+AllowRedefinitionToAddDeleteMethods`.
 */
class CoreMainThreadSafetyTest {
    @Test
    fun aMainDispatcherCallerBlocksNoThreadAndTouchesNoSocket() =
        runBlocking {
            skipOnMissingNativeLib(CoreMainThreadSafetyTest::class) {
                if (!blockHoundInstalled) return@skipOnMissingNativeLib
                withEchoServer { port ->
                    val recorder = arrayOfNulls<RecordingIo>(1)
                    val client = connectCore(port) { RecordingIo(it).also { io -> recorder[0] = io } }
                    val io = recorder[0]!!
                    val payload = Random(64).nextBytes(64 * 1024)
                    // The same calls once off the main thread first: class loading and the first native use are
                    // one-time costs this test is not about.
                    withContext(Dispatchers.Default) { assertContentEquals(payload, echoOver(client.connection.openStream(), payload)) }
                    violations.clear()

                    val main = Executors.newSingleThreadExecutor { MainLike(it) }
                    Dispatchers.setMain(main.asCoroutineDispatcher())
                    val echoed =
                        try {
                            withContext(Dispatchers.Main) {
                                check(Thread.currentThread() is MainLike)
                                val stream = client.connection.openStream()
                                val back = echoOver(stream, payload)
                                client.connection.close(QuicError.NoError)
                                // A connect from the main thread: its setup runs off it, its handshake wait parks.
                                val second = connectCore(port)
                                val secondBack = echoOver(second.connection.openStream(), payload)
                                assertContentEquals(payload, secondBack, "the echo of the main thread's own connect")
                                second.connection.close(QuicError.NoError)
                                back
                            }
                        } finally {
                            Dispatchers.resetMain()
                            main.shutdownNow()
                        }
                    assertContentEquals(payload, echoed, "the echo on the main dispatcher")
                    val report =
                        violations.joinToString(
                            "\n\n",
                        ) { v -> v.message + "\n" + v.stackTrace.joinToString("\n") { "    at $it" } }
                    assertTrue(violations.isEmpty(), "blocking calls on the main dispatcher:\n$report")
                    val socketCallsByMain = (io.sendsByThread() + io.receivesByThread()).keys.filter { it == MAIN_NAME }
                    assertEquals(emptyList(), socketCallsByMain, "the main thread sent or received on the socket")
                    println(
                        "[core main-safety] sends by thread=${io.sendsByThread()} receives by thread=${io.receivesByThread()} " +
                            "handed to loop=${client.conn.counts.handedToLoop} in place=${client.conn.counts.enteredInPlace}",
                    )
                }
            }
        }

    /** The control: a blocking call on the main-like thread is reported, so a silent pass above means something. */
    @Test
    fun theControlASleepOnTheMainThreadIsCaught() =
        runBlocking {
            if (!blockHoundInstalled) return@runBlocking
            violations.clear()
            val main = Executors.newSingleThreadExecutor { MainLike(it) }
            try {
                withContext(main.asCoroutineDispatcher()) { runCatching { Thread.sleep(1) } }
            } finally {
                main.shutdownNow()
            }
            assertTrue(violations.isNotEmpty(), "BlockHound did not report a sleep on the main-like thread")
        }

    private class MainLike(
        runnable: Runnable,
    ) : Thread(runnable, MAIN_NAME)

    private object MainLikeIntegration : BlockHoundIntegration {
        override fun applyTo(builder: BlockHound.Builder) {
            builder
                .nonBlockingThreadPredicate { current -> current.or { it is MainLike } }
                // The main-like thread's own machinery: its idle wait for the next task and its exit.
                .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "getTask")
                .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "processWorkerExit")
                .blockingMethodCallback { method ->
                    val error = BlockingOperationError(method)
                    if (Thread.currentThread() is MainLike) {
                        violations += error
                        throw error
                    }
                }
        }
    }

    private companion object {
        /** True once BlockHound is installed, once per JVM; on a JDK it cannot instrument the tests are skipped, loudly. */
        val blockHoundInstalled: Boolean by lazy { install() }

        fun install(): Boolean {
            try {
                kotlin.test.asserter
                BlockHound.install(MainLikeIntegration)
                return true
            } catch (unsupported: Throwable) {
                recordSkip(
                    CoreMainThreadSafetyTest::class,
                    SkipReason.HostBehaviourDiffers("BlockHound could not instrument JDK ${Runtime.version()}: $unsupported"),
                    SkipGate.HostCannotProvideIt("BlockHound instrumentation of JDK ${Runtime.version().feature()}"),
                )
                return false
            }
        }

        const val MAIN_NAME = "core-main"
        val violations = ConcurrentLinkedQueue<BlockingOperationError>()
    }
}
