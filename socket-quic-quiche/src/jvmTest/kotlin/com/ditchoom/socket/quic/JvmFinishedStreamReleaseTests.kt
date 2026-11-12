package com.ditchoom.socket.quic

/** JVM member of [FinishedStreamReleaseTestSuite]; the backend is whichever `-PquicheJvmBackend` selected. */
class JvmFinishedStreamReleaseTests : FinishedStreamReleaseTestSuite() {
    override fun simEnv(): MigrationSimEnv = jvmMigrationSimEnv()

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            recordMissingNativeLib(JvmFinishedStreamReleaseTests::class, e)
        }
    }
}
