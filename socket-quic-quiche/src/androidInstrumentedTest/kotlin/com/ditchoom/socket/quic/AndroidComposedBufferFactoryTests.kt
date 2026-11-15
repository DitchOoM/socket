package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Android (quiche JNI) member of [ComposedBufferFactoryTestSuite]. */
@RunWith(AndroidJUnit4::class)
class AndroidComposedBufferFactoryTests : ComposedBufferFactoryTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidComposedBufferFactoryTests::class, block)
}
