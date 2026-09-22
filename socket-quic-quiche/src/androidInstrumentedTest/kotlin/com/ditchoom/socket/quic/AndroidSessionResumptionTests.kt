package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Android (quiche JNI) member of [SessionResumptionTestSuite]. */
@RunWith(AndroidJUnit4::class)
class AndroidSessionResumptionTests : SessionResumptionTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidSessionResumptionTests::class, block)
}
