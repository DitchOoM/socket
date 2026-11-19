package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.core.testing.assumeNativeStack
import com.ditchoom.socket.quic.loadNetShim
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The spec block's offsets are written once in `qnet.h`; this code's copy must agree with it and with the loaded library. */
class SpecLayoutTests {
    @Test
    fun theKotlinOffsetsAreTheHeadersOffsets() {
        val header = File("src/jni/qnet.h").readText()

        fun define(name: String): Int =
            Regex("#define QN_SPEC_$name (\\d+)")
                .find(header)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: throw AssertionError("qnet.h has no QN_SPEC_$name")
        assertEquals(define("BIND"), SpecLayout.BIND)
        assertEquals(define("PEER"), SpecLayout.PEER)
        assertEquals(define("RCVBUF"), SpecLayout.RCVBUF)
        assertEquals(define("SNDBUF"), SpecLayout.SNDBUF)
        assertEquals(define("GRANTED_RCV"), SpecLayout.GRANTED_RCV)
        assertEquals(define("GRANTED_SND"), SpecLayout.GRANTED_SND)
        assertEquals(define("LOCAL"), SpecLayout.LOCAL)
        assertEquals(define("BYTES"), SpecLayout.BYTES)
    }

    @Test
    fun theLoadedLibraryReportsTheKotlinLayout() {
        assumeNativeStack()
        assertEquals(SpecLayout.FINGERPRINT, loadNetShim().specLayout())
    }
}
