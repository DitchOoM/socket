package com.ditchoom.socket.quic

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Regression tests for the CI crash on run 28609148354 (`:socket-http3:jvmTest`, SIGSEGV
 * `SEGV_ACCERR` — a bare native thread jumping to unmapped memory at thread exit).
 *
 * Root cause: `loadQuicheApi()` loaded a fresh libquiche copy per call (one per QUIC
 * connection/server) and the FFM loader bound it to `Arena.ofAuto()`, so any copy whose api
 * instance became unreachable was **dlclosed by GC**. BoringSSL registers pthread TLS destructors
 * that glibc never unregisters on dlclose — any thread that ever called into an unloaded copy
 * (e.g. an idle-timing-out Dispatchers.Default worker) runs a destructor pointing into unmapped
 * pages when it exits. The hs_err showed 8+ simultaneously-mapped `/tmp/quiche-ffm*` copies and
 * zero class-unload events (ruling out classloader GC — it was arena-lifecycle unload).
 *
 * The invariant that kills the whole failure class: **the quiche native library must be loaded
 * once per process and must never become unloadable.** Unloadability is directly observable —
 * if a `WeakReference` to the loaded api can clear, the library can be dlclosed.
 */
class QuicheApiLifecycleTest {
    @Test
    fun loadQuicheApiReturnsAProcessWideSingleton() {
        // One native library per process: repeated loads (every connection/server calls this)
        // must not extract + dlopen fresh copies.
        assertSame(
            loadQuicheApi(),
            loadQuicheApi(),
            "loadQuicheApi() must memoize — per-call loading maps a fresh libquiche copy per " +
                "QUIC connection and makes each copy GC-unloadable (CI SIGSEGV, run 28609148354)",
        )
    }

    @Test
    fun quicheApiIsNeverGcUnloadable() {
        var api: QuicheApi? = loadQuicheApi()
        val ref = WeakReference(api)
        @Suppress("UNUSED_VALUE")
        api = null
        // If the api is only reachable through callers, GC clears this ref and (on the FFM
        // backend) the auto-arena Cleaner dlcloses libquiche — while BoringSSL pthread TLS
        // destructors registered by any thread that used it still point into the mapping.
        repeat(20) {
            if (ref.get() == null) return@repeat
            System.gc()
            Thread.sleep(25)
        }
        assertNotNull(
            ref.get(),
            "the loaded QuicheApi became unreachable → its native library is GC-unloadable. " +
                "BoringSSL can never be safely unloaded (pthread TLS destructors outlive dlclose); " +
                "the loader must hold the api for the process lifetime, in Arena.global().",
        )
    }
}
