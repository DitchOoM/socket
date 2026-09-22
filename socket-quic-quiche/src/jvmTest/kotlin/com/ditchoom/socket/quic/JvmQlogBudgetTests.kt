package com.ditchoom.socket.quic

import java.io.File
import java.nio.file.Files

/** JVM member of [QlogBudgetTestSuite]: whichever backend `-PquicheJvmBackend` selected, measured with `java.io.File`. */
class JvmQlogBudgetTests : QlogBudgetTestSuite() {
    override fun simEnv(): MigrationSimEnv = jvmMigrationSimEnv()

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmQlogBudgetTests::class, block)

    override fun freshDirectory(tag: String): String = Files.createTempDirectory(tag).toFile().absolutePath

    override fun filesIn(dir: String): Map<String, Long> =
        File(dir)
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .associate { it.name to it.length() }

    override fun readText(path: String): String = File(path).readText()
}
