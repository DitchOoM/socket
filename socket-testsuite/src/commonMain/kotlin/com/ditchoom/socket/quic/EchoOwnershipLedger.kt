package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.sync.Mutex

/**
 * Server-side buffer-ownership ledger for the echo harness.
 *
 * [TrackingBufferFactory] can only see buffers allocated from the factory it wraps, and the only factory
 * a test can inject is the **client's** (`TransportConfig.bufferFactory`). A QUIC server binds with a
 * hardcoded `BufferFactory.network()` — `QuicEngine.bind` has no buffer-factory seam — so the server half
 * of every echo test was, until now, entirely unmeasured. That blind spot is not hypothetical: it is
 * exactly how eleven echo-loop leaks survived in this repo's own harnesses long enough to become the
 * allocator primer behind #401, with the client-side assertion green the whole time, because the leaking
 * buffers were never the client's.
 *
 * This ledger closes that half. It does not — and cannot — audit the server driver's *internal*
 * allocations; it audits the thing that actually broke: **whether harness code released every buffer a
 * server-side `read()` transferred to it.** QUIC `read()` transfers ownership and `write()` takes none, so
 * an echo loop that writes a buffer and walks away leaks it, permanently starving the driver's
 * `streamReadPool` and turning every later drain into a fresh malloc.
 *
 * ## The two calls are deliberately separate
 * [took] is called at the **read boundary**, the instant `read()` hands a buffer over; [release] performs
 * the `freeIfNeeded()` and retires the receipt. Folding both into one "echo and free for me" helper would
 * make the ledger structurally unable to fail — the record of a release would be written by the same call
 * that performs it, so a dropped free would take its own evidence with it. Splitting them is what makes a
 * missing free *observable*: the receipt stays outstanding and [assertNoLeaks] names it. Because
 * [release] is the only thing that frees, "forgot to release" and "forgot to free" are the same bug, which
 * is precisely the bug that shipped eleven times.
 *
 * ## What it deliberately does not claim
 * The ledger sees only buffers routed through [took]. An echo loop that reads a buffer and never tells the
 * ledger at all is invisible to it — no bookkeeping scheme detects code that opts out of bookkeeping. The
 * guard against that is that this suite offers exactly one echo loop and it funnels every read through
 * [took]. So: an unbalanced ledger is always a real leak; a balanced one is strong evidence, not proof.
 *
 * **Thread-safe.** Echo loops run one coroutine per stream across dozens of concurrent streams on K/N as
 * well as JVM, so both the counters and the outstanding list are guarded. Same spin-lock idiom as
 * [TrackingBufferFactory]: a [Mutex] via `tryLock`, because the entry points are non-suspending and each
 * critical section is a single list mutation.
 */
class EchoOwnershipLedger {
    private val listLock = Mutex()
    private val outstanding = mutableListOf<Receipt>()
    private var takenTotal = 0
    private var releasedTotal = 0

    private inline fun <T> locked(block: () -> T): T {
        while (!listLock.tryLock()) {
            // Spin: the critical section is a single list mutation, contention is rare.
        }
        try {
            return block()
        } finally {
            listLock.unlock()
        }
    }

    /**
     * Record that the harness has taken ownership of [buffer] from a server-side `read()`.
     *
     * Call this immediately after the read returns, before any work that could suspend or throw — the
     * receipt is the only thing that makes a subsequent leak visible.
     */
    fun took(buffer: ReadBuffer): Receipt {
        val receipt = Receipt(buffer, Throwable("Received from read() at"))
        locked {
            outstanding.add(receipt)
            takenTotal++
        }
        return receipt
    }

    /**
     * Free the buffer behind [receipt] and retire it from the ledger.
     *
     * Call from a `finally`, so a cancelled or throwing write still releases: [freeIfNeeded] does not
     * suspend and therefore runs even once the coroutine is cancelled. Idempotent — a double release
     * frees once (via `freeIfNeeded`) and counts once.
     */
    fun release(receipt: Receipt) {
        receipt.buffer.freeIfNeeded()
        locked {
            if (outstanding.remove(receipt)) releasedTotal++
        }
    }

    /** Buffers taken from `read()` so far. */
    val takenCount: Int get() = locked { takenTotal }

    /** Buffers released so far. */
    val releasedCount: Int get() = locked { releasedTotal }

    /** Buffers taken but not yet released. */
    val outstandingCount: Int get() = locked { outstanding.size }

    /**
     * Assert the harness released every buffer the server handed it.
     *
     * Call **after** the enclosing `coroutineScope` has returned: the echo coroutines are children of the
     * server scope, which `withQuicServer` closes in its `finally`, so by that point every `finally` that
     * releases a buffer has already run. Asserting earlier — between `serverJob.cancel()` and the scope
     * join — would race a buffer that is about to be released, and flake.
     */
    fun assertNoLeaks(context: String) {
        val leaked = locked { outstanding.toList() }
        if (leaked.isEmpty()) return

        val details =
            leaked.joinToString("\n") { receipt ->
                "  Buffer (${receipt.buffer})\n    ${
                    receipt.site.stackTraceToString().lines().take(5).joinToString("\n    ")
                }"
            }
        throw AssertionError(
            "$context: the server echo harness leaked ${leaked.size} buffer(s) " +
                "(took $takenCount from read(), released $releasedCount) — read transfers ownership, so " +
                "every echoed buffer must be released:\n$details",
        )
    }

    class Receipt internal constructor(
        internal val buffer: ReadBuffer,
        internal val site: Throwable,
    )
}
