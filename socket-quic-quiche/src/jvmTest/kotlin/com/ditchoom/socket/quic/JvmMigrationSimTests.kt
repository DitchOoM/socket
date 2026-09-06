package com.ditchoom.socket.quic

/**
 * JVM member of [MigrationSimTestSuite].
 *
 * The backend is whichever `-PquicheJvmBackend` selected (JNI by default, FFM on the JDK-21 lane), so
 * this member covers both of the JVM's two bindings across CI. Fixtures come off the test classpath;
 * the Kotlin/Native members probe the filesystem instead, which is exactly what [MigrationSimEnv]
 * exists to abstract.
 */
class JvmMigrationSimTests : MigrationSimTestSuite() {
    override fun simEnv(): MigrationSimEnv = jvmMigrationSimEnv()

    /**
     * The JVM loads `libquiche` at runtime, so a lane without the native present must report a typed
     * skip rather than a failure. `UnsatisfiedLinkError` is a JVM type, which is precisely why this
     * lives here and not in the shared suite.
     */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            recordMissingNativeLib(JvmMigrationSimTests::class, e)
        }
    }
}
