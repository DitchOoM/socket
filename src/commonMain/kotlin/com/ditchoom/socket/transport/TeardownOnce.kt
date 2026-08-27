package com.ditchoom.socket.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs a resource's teardown exactly once, however many callers race `close()`.
 *
 * Three classes in this package arrived at the same seven lines independently — [CodecConnection]
 * (#471), [CodecSender] (#471), and [ReconnectingConnection], which was edited 21 minutes after
 * #471 landed and kept the defect that issue had just removed, one field over from the guard #473
 * replaced in the same file. The lesson lived in a commit message, and that was not enough for it to
 * travel. It is a type now.
 *
 * ## Why a latch rather than a flag
 *
 * `if (closed) return; closed = true` is check-then-act. `@Volatile` publishes the write; it does not
 * make the pair atomic, so concurrent closers all pass the guard and all run teardown. Measured
 * across this package: 223/300 attempts in [CodecConnection] (worst case all eight closers, each
 * calling `release()` on a deque that is not thread-safe), and 20/300 in [ReconnectingConnection],
 * where both closers closed the same inner connection.
 *
 * `CompletableDeferred.complete()` returns true for exactly one concurrent caller, so the winner is
 * decided by the same atomic operation that records the decision — there is no window between
 * deciding and recording for a second caller to slip through.
 *
 * ## Why losers wait
 *
 * Returning early is itself a defect, not a shortcut: a caller whose `close()` returns while the
 * resource is still being torn down has been told the resource is closed when it is not — for up to
 * the drain budget, in [CodecConnection]'s case. Losers await [finished] instead. A loser whose own
 * coroutine is already cancelled resumes from that await immediately, which is correct rather than a
 * hole: the winner completes teardown under [NonCancellable] regardless of what any loser does.
 *
 * ## Why NonCancellable
 *
 * The canonical call site is `finally { close() }`, and the usual reason control reached that
 * `finally` is that the caller was cancelled. Without it the first suspension point in teardown
 * throws `CancellationException` and every step after it is skipped. Measured in [CodecConnection]:
 * the transport was still open after a cancelled caller's `close()`, and because `TypedMuxView`
 * wraps its close in `runCatching`, the leak was entirely silent.
 *
 * ## What this is deliberately not
 *
 * Not a general "run once" helper. [CodecConnection.releaseResourcesOnce] is a bare
 * `CompletableDeferred.complete()` on purpose: it is not suspending and its losers return rather
 * than wait, because nothing is waiting on a resource release the way a caller waits on `close()`.
 * Reusing this type there would impose an await and a [NonCancellable] hop that shape does not want.
 *
 * Nor should the latch be some other call's return value — the `outbound` channel's own `close()`
 * is the tempting one in [CodecConnection]. The writer closes that channel from its own `finally` on
 * failure and on scope cancellation, so on exactly the paths where cleanup matters most a real
 * `close()` caller would be told it lost a race it never entered and would skip teardown entirely.
 * The latch has to be one that only `close()` touches, which is why this type owns it.
 */
internal class TeardownOnce {
    private val started = CompletableDeferred<Unit>()
    private val finished = CompletableDeferred<Unit>()

    /**
     * Whether teardown has begun — the fence entry points fast-fail on ("this is closed, your call
     * is a mistake").
     *
     * [CodecConnection] and [CodecSender] historically kept this as a separate `@Volatile var closed`
     * assigned on the line after the latch was won, and documented the fence and the latch as
     * distinct roles. The roles are distinct; the *instants* never were — every site set the flag
     * immediately on winning. Deriving it keeps the role separation while removing both the window
     * between winning and publishing, and the possibility of a teardown path that forgets to publish
     * at all.
     */
    val begun: Boolean get() = started.isCompleted

    /**
     * Runs [teardown] on the first caller and suspends every other caller until it has finished.
     *
     * Safe to call any number of times and from any number of coroutines; after the first completes,
     * later calls return as soon as they observe [finished].
     */
    suspend fun runOnce(teardown: suspend () -> Unit) {
        if (!started.complete(Unit)) {
            finished.await()
            return
        }
        try {
            withContext(NonCancellable) { teardown() }
        } finally {
            finished.complete(Unit)
        }
    }
}
