package com.ditchoom.socket.quic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM member of [StreamsBlockTestSuite]: whichever backend `-PquicheJvmBackend` selected, so JNI's and FFM's entries both run. */
class JvmStreamsBlockTests : StreamsBlockTestSuite() {
    override fun simEnv(): MigrationSimEnv = jvmMigrationSimEnv()

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmStreamsBlockTests::class, block)

    /** The block's offsets are written once in `qnet_streams.h`; this code's copy must agree with it. */
    @Test
    fun theKotlinOffsetsAreTheHeadersOffsets() {
        val header = File("src/jni/qnet_streams.h").readText()

        fun define(name: String): Int =
            Regex("#define QN_STREAMS_$name \\(?(-?\\d+)\\)?")
                .find(header)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: throw AssertionError("qnet_streams.h has no QN_STREAMS_$name")
        assertEquals(define("READABLE"), STREAMS_READABLE)
        assertEquals(define("WRITABLE"), STREAMS_WRITABLE)
        assertEquals(define("FULL"), STREAMS_FULL)
        assertEquals(define("IDS"), STREAMS_IDS)
        assertEquals(define("ID_BYTES"), STREAMS_ID_BYTES)
        assertEquals(define("SPILLED"), STREAMS_SPILLED)
    }
}
