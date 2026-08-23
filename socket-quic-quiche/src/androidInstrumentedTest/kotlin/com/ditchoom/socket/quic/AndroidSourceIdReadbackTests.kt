package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Android (quiche **JNI**) member of [SourceIdReadbackTestSuite] — the backend we ship to devices. */
@RunWith(AndroidJUnit4::class)
class AndroidSourceIdReadbackTests : SourceIdReadbackTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidSourceIdReadbackTests::class, block)
}
