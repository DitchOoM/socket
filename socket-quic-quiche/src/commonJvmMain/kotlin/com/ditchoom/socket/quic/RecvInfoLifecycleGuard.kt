package com.ditchoom.socket.quic

import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-gated lifecycle guard for `quiche_recv_info` allocations.
 *
 * The intermittent `build-linux` JNI SIGSEGV is a use-after-free: quiche reads `recv_info->from`
 * inside `quiche_conn_recv` (`std_addr_from_c`) after the allocation has been `free()`d and its
 * first bytes recycled into the glibc tcache free-list — so quiche dereferences a free-list
 * pointer as a `sockaddr`. The hs_err names the *use* site (`QuicheDriver.execute -> connRecv`) but
 * not the *free* site, because no core dump / other-thread stack survives.
 *
 * This guard closes that gap. When enabled it tracks every recv_info handle's liveness and, the
 * instant a `connRecv` (or a second `recvInfoFree`) touches a freed handle, prints the captured
 * *freeing* stack + the offending *use* stack and aborts — turning the next occurrence into a
 * precise, deterministic diagnostic that names the racing free path (eviction / server close /
 * migration teardown / driver cleanup) instead of an opaque native SIGSEGV minutes later.
 *
 * Enabled by `-Dquic.recvInfoGuard=1` or `QUIC_RECVINFO_GUARD=1` (CI sets the env on the jvmTest
 * worker). Off by default: the per-call [ConcurrentHashMap] lookup is debug-only overhead and must
 * never be on a production hot path.
 *
 * Limitation: liveness is keyed by native address, so if malloc hands the freed address back out as
 * a *fresh* recv_info ([recvInfoNew]) before the stale use, the slot reads live and the use is
 * missed. The observed crash recycles the chunk into the free-list (not back into a recv_info), so
 * the guard catches it; a generation-tagged handle would be needed to be reuse-proof, which is more
 * invasive than this diagnostic warrants.
 */
internal object RecvInfoLifecycleGuard {
    private val ACTIVE = setOf("1", "true", "yes", "on")

    val enabled: Boolean =
        System.getProperty("quic.recvInfoGuard")?.lowercase() in ACTIVE ||
            System.getenv("QUIC_RECVINFO_GUARD")?.lowercase() in ACTIVE

    private sealed interface Entry

    private object Alive : Entry

    private class Freed(
        val thread: String,
        val stack: Array<StackTraceElement>,
    ) : Entry

    // ConcurrentHashMap forbids null values, so liveness is an explicit [Alive] sentinel rather
    // than a null mapping.
    private val state = ConcurrentHashMap<Long, Entry>()

    fun onNew(handle: Long) {
        // A fresh allocation reclaims the slot — legitimate even if malloc reused a freed address.
        state[handle] = Alive
    }

    fun onFree(handle: Long) {
        val prior = state[handle]
        if (prior is Freed) {
            report("DOUBLE FREE of recv_info", handle, prior)
        }
        state[handle] = Freed(Thread.currentThread().name, Throwable().stackTrace)
    }

    /** Returns false if the handle is already freed (caller must NOT forward to quiche). */
    fun onUse(handle: Long): Boolean {
        val freed = state[handle] as? Freed ?: return true
        report("USE-AFTER-FREE of recv_info in connRecv", handle, freed)
        return false
    }

    private fun report(
        what: String,
        handle: Long,
        freed: Freed,
    ): Nothing {
        val banner = "===== RECV_INFO LIFECYCLE VIOLATION ====="
        val msg =
            buildString {
                appendLine(banner)
                appendLine("$what (handle=0x${handle.toString(16)})")
                appendLine("freed by thread [${freed.thread}] at:")
                freed.stack.take(25).forEach { appendLine("    at $it") }
                appendLine("offending access on thread [${Thread.currentThread().name}] at:")
                Throwable().stackTrace.take(25).forEach { appendLine("    at $it") }
                append(banner)
            }
        System.err.println(msg)
        System.err.flush()
        // Throw so the test worker fails with this diagnostic deterministically, rather than
        // limping on to the native SIGSEGV that erases which free raced.
        throw IllegalStateException(what + " (handle=0x${handle.toString(16)}) — see stderr for the freeing stack")
    }
}

/**
 * Returns [api] wrapped in the recv_info lifecycle guard when it's enabled, else [api] unchanged.
 * Call from every `loadQuicheApi` so both the JNI and FFM backends are covered. Public (not
 * `internal`) because the JDK 21+ FFM loader lives in the separate `jvm21Main` multi-release source
 * set, which can only see public common symbols; the guard types it delegates to stay internal.
 */
fun maybeGuardRecvInfo(api: QuicheApi): QuicheApi = if (RecvInfoLifecycleGuard.enabled) RecvInfoGuardQuicheApi(api) else api

/**
 * Wraps a [QuicheApi] to route recv_info new/free/use through [RecvInfoLifecycleGuard]. Kotlin
 * interface delegation (`by delegate`) forwards every other call unchanged, so only the three
 * lifecycle-relevant methods carry guard overhead.
 */
internal class RecvInfoGuardQuicheApi(
    private val delegate: QuicheApi,
) : QuicheApi by delegate {
    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo =
        delegate
            .recvInfoNew(fromAddr, fromAddrLen, toAddr, toAddrLen)
            .also { RecvInfoLifecycleGuard.onNew(it.handle) }

    override fun recvInfoFree(info: QuicheRecvInfo) {
        RecvInfoLifecycleGuard.onFree(info.handle)
        delegate.recvInfoFree(info)
    }

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int {
        if (!RecvInfoLifecycleGuard.onUse(recvInfo.handle)) {
            // Freed handle — onUse already reported + threw. Unreachable, but keep the type checker
            // happy without forwarding the use to quiche.
            error("unreachable")
        }
        return delegate.connRecv(conn, buf, bufLen, recvInfo)
    }
}
