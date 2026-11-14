package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.MigrationSimEnv
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import com.ditchoom.socket.quic.recordMissingNativeLib

/** JVM member of [CaptureReplayTestSuite], on whichever `libquiche` binding `-PquicheJvmBackend` selected. */
class JvmCaptureReplayTests : CaptureReplayTestSuite() {
    override fun simEnv(): MigrationSimEnv = jvmMigrationSimEnv()

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            recordMissingNativeLib(JvmCaptureReplayTests::class, e)
        }
    }
}
