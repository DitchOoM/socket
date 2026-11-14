package com.ditchoom.socket.quic

/** JVM member of [ConcurrentRequestsTestSuite]; the backend is whichever `-PquicheJvmBackend` selected. */
class JvmConcurrentRequestsTests : ConcurrentRequestsTestSuite() {
    override fun simEnv(): MigrationSimEnv = jvmMigrationSimEnv()

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            recordMissingNativeLib(JvmConcurrentRequestsTests::class, e)
        }
    }
}
