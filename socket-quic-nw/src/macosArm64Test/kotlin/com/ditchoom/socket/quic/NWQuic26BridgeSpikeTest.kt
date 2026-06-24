@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.nwquic26.NWQuic26Bridge
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PLAN step 1 (build-plumbing de-risk): proves the swiftc -> static archive -> generated ObjC
 * header -> cinterop -> Kotlin/Native call chain works end-to-end on macosArm64. It exercises a
 * trivial @objc shim only; the real OS-26 NetworkConnection<QUIC> bridge is step 2.
 */
class NWQuic26BridgeSpikeTest {
    @Test
    fun staticScalarMarshals() {
        assertEquals(42, NWQuic26Bridge.ping(16))
    }

    @Test
    fun instanceObjcStringMarshals() {
        assertEquals("nwquic26-bridge-ok", NWQuic26Bridge().greeting())
    }
}
