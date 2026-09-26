package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Android (quiche JNI) member of [PmtuPathEventTestSuite]. */
@RunWith(AndroidJUnit4::class)
class AndroidPmtuPathEventTests : PmtuPathEventTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidPmtuPathEventTests::class, block)
}
