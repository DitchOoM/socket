package com.ditchoom.socket.mainthread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import reactor.blockhound.BlockHound
import reactor.blockhound.BlockingOperationError
import reactor.blockhound.integration.BlockHoundIntegration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The thread a UI framework would call us from: the Android main thread, a desktop toolkit's event
 * thread, `Dispatchers.Main`. BlockHound treats it as non-blocking, so any blocking call made on it
 * is a violation.
 */
class MainLikeThread(
    runnable: Runnable,
) : Thread(runnable, "main-like")

/**
 * Installs BlockHound once per JVM and records every violation it reports.
 *
 * The callback records before it throws because the throw alone is not enough: library code that
 * catches `Throwable` (a `runCatching`, a cleanup path) would swallow the [BlockingOperationError],
 * and the scenario would pass. The record is what [runOnMainLike] asserts on.
 */
object MainThreadSafety {
    private val violations = ConcurrentLinkedQueue<BlockingOperationError>()

    private val installed: Unit by lazy {
        // kotlin.test finds its asserter through ServiceLoader (a jar read) the first time an assertion
        // runs. That is the test's own blocking, not the library's, so it happens here, before any
        // scenario, rather than on the main-like thread in the middle of one.
        kotlin.test.asserter
        // install() also loads every BlockHoundIntegration on the classpath through ServiceLoader, which
        // is how kotlinx-coroutines-debug's CoroutinesBlockHoundIntegration gets applied: it marks the
        // Dispatchers.Default workers non-blocking too, and allows the scheduler's own parking.
        BlockHound.install(MainLikeIntegration)
    }

    fun install() = installed

    fun drain(): List<BlockingOperationError> {
        val drained = mutableListOf<BlockingOperationError>()
        while (true) drained += violations.poll() ?: break
        return drained
    }

    private object MainLikeIntegration : BlockHoundIntegration {
        override fun applyTo(builder: BlockHound.Builder) {
            builder
                .nonBlockingThreadPredicate { current -> current.or { it is MainLikeThread } }
                // The main-like thread's own machinery, not work it was given: its idle wait for the next
                // task (a looper waiting for a message) and its exit when the test shuts it down.
                .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "getTask")
                .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "processWorkerExit")
                // BlockHound's defaults cover file and socket I/O, sleeping and parking. These are the
                // blocking calls it does not know about that a first network operation can make.
                // DNS: every InetAddress lookup ends in one of these natives.
                .markAsBlocking("java.net.Inet6AddressImpl", "lookupAllHostAddr", "(Ljava/lang/String;I)[Ljava/net/InetAddress;")
                .markAsBlocking("java.net.Inet4AddressImpl", "lookupAllHostAddr", "(Ljava/lang/String;)[Ljava/net/InetAddress;")
                // Native library loading: dlopen of a multi-megabyte library (JNI and FFM respectively).
                .markAsBlocking(
                    "jdk.internal.loader.NativeLibraries",
                    "load",
                    "(Ljdk/internal/loader/NativeLibraries\$NativeLibraryImpl;Ljava/lang/String;ZZ)Z",
                ).markAsBlocking(
                    "jdk.internal.loader.RawNativeLibraries",
                    "load0",
                    "(Ljdk/internal/loader/RawNativeLibraries\$RawNativeLibraryImpl;Ljava/lang/String;)Z",
                ).blockingMethodCallback { method ->
                    val error = BlockingOperationError(method)
                    if (Thread.currentThread() is MainLikeThread) {
                        violations += error
                        throw error
                    }
                    // The coroutine integration also marks Dispatchers.Default's workers non-blocking.
                    // Blocking there is worth knowing about but is not what this suite enforces (the
                    // caller's thread), so it is reported and the library is left to carry on.
                    System.err.println("note: blocking call on ${Thread.currentThread().name} (not the main-like thread): ${error.message}")
                }
        }
    }
}

/**
 * Runs [block] on a fresh [MainLikeThread] (via a single-thread dispatcher, the shape of
 * `Dispatchers.Main`) and fails if anything it called blocked that thread, printing every
 * violation's stack trace. Everything [block] calls directly — and every continuation it resumes —
 * runs on the main-like thread; work the library moves to its own dispatchers does not.
 */
fun runOnMainLike(
    timeout: Duration = 60.seconds,
    block: suspend CoroutineScope.() -> Unit,
) {
    MainThreadSafety.install()
    MainThreadSafety.drain()
    val executor = Executors.newSingleThreadScheduledExecutor { MainLikeThread(it) }
    var failure: Throwable? = null
    try {
        runBlocking {
            withContext(executor.asCoroutineDispatcher()) {
                check(Thread.currentThread() is MainLikeThread) { "not on the main-like thread" }
                withTimeout(timeout) { block() }
            }
        }
    } catch (t: Throwable) {
        failure = t
    } finally {
        executor.shutdownNow()
    }
    val violations = MainThreadSafety.drain()
    if (violations.isNotEmpty()) {
        val report =
            violations.joinToString("\n\n") { violation ->
                "${violation.message}\n" + violation.stackTrace.joinToString("\n") { "    at $it" }
            }
        System.err.println("MAIN-THREAD VIOLATIONS (${violations.size}):\n$report")
        val error = AssertionError("${violations.size} blocking call(s) on the main-like thread:\n$report", failure)
        throw error
    }
    failure?.let { throw it }
}

/** Fails unless [block] runs on the main-like thread — a guard that a scenario did not hop off it. */
fun assertOnMainLike(what: String) {
    if (Thread.currentThread() !is MainLikeThread) fail("$what resumed on ${Thread.currentThread().name}, not the main-like thread")
}
