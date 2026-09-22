@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.appleSockAddrLayout
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/** Apple K/Native member of [QlogBudgetTestSuite], measured with Foundation's file manager. */
class AppleQlogBudgetTests : QlogBudgetTestSuite() {
    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = CinteropQuicheApi,
            certChainPath = AppleTestCerts.tlsConfig.certChainPath,
            privKeyPath = AppleTestCerts.tlsConfig.privKeyPath,
            codec = SocketAddressCodec(appleSockAddrLayout),
        )

    override fun freshDirectory(tag: String): String {
        val path = "${NSTemporaryDirectory().trimEnd('/')}/$tag-${NSProcessInfo.processInfo.globallyUniqueString}"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        return path
    }

    override fun filesIn(dir: String): Map<String, Long> {
        val manager = NSFileManager.defaultManager
        return manager
            .contentsOfDirectoryAtPath(dir, null)
            .orEmpty()
            .map { it as String }
            .mapNotNull { name ->
                val attributes = manager.attributesOfItemAtPath("$dir/$name", null) ?: return@mapNotNull null
                if (attributes[NSFileType] != NSFileTypeRegular) return@mapNotNull null
                name to (attributes[NSFileSize] as NSNumber).longLongValue
            }.toMap()
    }

    override fun readText(path: String): String = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: ""
}
