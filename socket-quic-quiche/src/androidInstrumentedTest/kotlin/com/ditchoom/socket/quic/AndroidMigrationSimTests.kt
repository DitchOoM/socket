package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import org.junit.runner.RunWith

/**
 * Android (quiche **JNI**) member of [MigrationSimTestSuite].
 *
 * This is the member closest to the incidents these scenarios encode. #393 — a migrated-from path's
 * in-flight packets left unackable, so a stream stalls while the connection stays healthy — was found
 * on an Android device, and #445's field measurements are Android measurements. Android also links its
 * own `libquiche.so`, built for a different triple than any desktop lane, so a green JVM or Apple run
 * says nothing about the binary that actually ships.
 *
 * Lives here rather than being inherited because `androidInstrumentedTest` deliberately does not
 * `dependsOn(commonTest)`; the suite reaches it through the `src/sharedQuicheTestSuites/kotlin` srcDir
 * both source sets compile. Both ends run in this process over loopback, so quiche needs a real cert
 * chain and key on disk — supplied by [AndroidTestCerts].
 */
@RunWith(AndroidJUnit4::class)
class AndroidMigrationSimTests : MigrationSimTestSuite() {
    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = loadQuicheApi(),
            certChainPath = AndroidTestCerts.tlsConfig.certChainPath,
            privKeyPath = AndroidTestCerts.tlsConfig.privKeyPath,
            codec = SocketAddressCodec(hostOsSockAddrLayout()),
        )

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidMigrationSimTests::class, block)
}
