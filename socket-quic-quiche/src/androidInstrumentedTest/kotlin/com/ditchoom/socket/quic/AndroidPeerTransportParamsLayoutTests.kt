package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Android (quiche **JNI**) member of [PeerTransportParamsLayoutTestSuite] — DitchOoM/socket#388.
 *
 * ## Why this member is not optional
 * The JNI binding for `quiche_conn_peer_transport_params` is **new in this change** and is the one
 * piece of the #388 fix that no other lane exercises: JVM-FFM computes byte offsets by hand, and the
 * two cinterop backends use a generated struct — three different readers, none of them this one. JVM's
 * default test task also runs JNI, but on x86_64/aarch64 desktop against the desktop `libquiche.dylib`;
 * this runs the same binding on real Android arm64 against the Android `libquiche.so` built for that
 * ABI from the same patched quiche clone.
 *
 * ## Why it lives in `androidInstrumentedTest` rather than being inherited
 * That source set deliberately does **not** `dependsOn(commonTest)` (see the module's
 * `build.gradle.kts`), so the suite reaches it through the `src/sharedQuicheTestSuites/kotlin` srcDir
 * both source sets compile. Without that, the guard would have covered JVM, Apple and Linux and
 * skipped precisely the backend whose binding is new — the "absent test, not red test" shape this
 * repo has been bitten by before.
 *
 * Both ends run in this process over loopback, exactly as [AndroidQuicLoopbackTests] does, so quiche
 * needs a real cert chain + key on disk — supplied by [AndroidTestCerts].
 */
@RunWith(AndroidJUnit4::class)
class AndroidPeerTransportParamsLayoutTests : PeerTransportParamsLayoutTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) =
        skipOnMissingNativeLib(AndroidPeerTransportParamsLayoutTests::class, block)
}
