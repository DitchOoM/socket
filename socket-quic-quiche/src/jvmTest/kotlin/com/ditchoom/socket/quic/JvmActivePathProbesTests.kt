package com.ditchoom.socket.quic

/** JVM member of [ActivePathProbesTestSuite]; the backend is whichever `-PquicheJvmBackend` selected. */
class JvmActivePathProbesTests : ActivePathProbesTestSuite() {
    override fun simEnv(): MigrationSimEnv = jvmMigrationSimEnv()
}
