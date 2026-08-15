package com.ditchoom.socket.quic

import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import org.junit.Assume.assumeTrue
import kotlin.reflect.KClass

/**
 * Run [block], turning a missing quiche native into a reported skip rather than a failure, attributed
 * to [site] — the suite class, named explicitly.
 *
 * [site] is a parameter and not an `Any` receiver because most call sites sit inside a `runBlocking` or
 * `runTest` body, where an extension on `Any` binds to the coroutine, not the suite: every one of them
 * used to record `site=BlockingCoroutine` / `site=TestScopeImpl`. See `recordSkip`.
 *
 * `loadQuicheApi()` is lazy, so a machine with no built JNI/FFM library raises
 * [UnsatisfiedLinkError] from inside the test body rather than at class-load. Catching it keeps
 * `./gradlew jvmTest` usable on a checkout that has never run the cargo build.
 *
 * This used to be copy-pasted — byte-identical — into 38 test classes, as either a private
 * `skipOnMissingNativeLib` or a `wrapTestBody` override. That is 38 chances for one copy to drift,
 * and, more to the point, 38 places that had to be found before anyone could ask the useful
 * question: *is this lane skipping the entire QUIC suite?* On CI the answer must always be no —
 * the pipeline builds the natives and hands every consuming lane the artifact — so those lanes set
 * `SOCKET_REQUIRE_ALL_TESTS=1` and [recordSkip] turns this path into a hard failure there.
 *
 * The [assumeTrue] is kept so that on a developer machine the test still reports as *skipped*
 * rather than passed. It is unreachable when the lane forbids skipping: [recordSkip] throws first.
 */
suspend fun skipOnMissingNativeLib(
    site: KClass<*>,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: UnsatisfiedLinkError) {
        recordMissingNativeLib(site, e)
    }
}

/**
 * The body of [skipOnMissingNativeLib]'s catch, for the handful of tests that need the `try` to
 * wrap only part of their body and so cannot delegate the whole thing.
 *
 * Call it from a `catch (e: UnsatisfiedLinkError)` and let the block end; it does not return
 * normally, because [assumeTrue]`(false)` aborts the test with an `AssumptionViolatedException`.
 */
fun recordMissingNativeLib(
    site: KClass<*>,
    e: UnsatisfiedLinkError,
) {
    recordSkip(site, SkipReason.NativeLibraryUnavailable(e.message ?: "UnsatisfiedLinkError with no message"))
    assumeTrue("Native lib not available: ${e.message}", false)
}
