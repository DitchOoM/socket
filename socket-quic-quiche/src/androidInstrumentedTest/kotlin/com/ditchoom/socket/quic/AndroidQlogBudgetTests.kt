package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import org.junit.runner.RunWith
import java.io.File

/** Android (quiche **JNI**) member of [QlogBudgetTestSuite], measured with `java.io.File` in the app's cache dir. */
@RunWith(AndroidJUnit4::class)
class AndroidQlogBudgetTests : QlogBudgetTestSuite() {
    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = loadQuicheApi(),
            certChainPath = AndroidTestCerts.tlsConfig.certChainPath,
            privKeyPath = AndroidTestCerts.tlsConfig.privKeyPath,
            codec = SocketAddressCodec(hostOsSockAddrLayout()),
        )

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidQlogBudgetTests::class, block)

    override fun freshDirectory(tag: String): String {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "$tag-${System.nanoTime()}").apply { mkdirs() }.absolutePath
    }

    override fun filesIn(dir: String): Map<String, Long> =
        File(dir)
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .associate { it.name to it.length() }

    override fun readText(path: String): String = File(path).readText()
}
