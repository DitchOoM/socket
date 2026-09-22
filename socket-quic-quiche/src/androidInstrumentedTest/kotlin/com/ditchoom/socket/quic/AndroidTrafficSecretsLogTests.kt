package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
import java.io.File

/** Android (quiche **JNI**) member of [TrafficSecretsLogTestSuite], writing into the app's private cache dir. */
@RunWith(AndroidJUnit4::class)
class AndroidTrafficSecretsLogTests : TrafficSecretsLogTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidTrafficSecretsLogTests::class, block)

    // java.nio.file needs API 26; this module's minSdk is 24.
    override fun newDirectory(): String =
        File.createTempFile("traffic-secrets", "", InstrumentationRegistry.getInstrumentation().context.cacheDir).run {
            if (!(delete() && mkdir())) throw AssertionError("cannot turn $this into a directory")
            absolutePath
        }

    override fun filesIn(dir: String): Map<String, String> =
        File(dir)
            .listFiles { f -> f.isFile }
            .orEmpty()
            .associate { it.name to it.readText() }
}
