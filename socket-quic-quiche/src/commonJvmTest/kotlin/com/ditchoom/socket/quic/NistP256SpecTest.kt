package com.ditchoom.socket.quic

import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The P-256 parameters the pin check compares against are stated as constants (Android before API 26 has
 * no EC AlgorithmParameters to resolve them from). Pin them to what the JCE says secp256r1 is, on a
 * runtime that can answer, so a typo in a constant cannot pass unnoticed.
 */
class NistP256SpecTest {
    @Test
    fun theStatedParametersAreTheJcesSecp256r1() {
        val jce =
            AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec("secp256r1"))
                getParameterSpec(ECParameterSpec::class.java)
            }
        assertEquals(jce.curve, nistP256Spec.curve, "field, a and b")
        assertEquals(jce.generator, nistP256Spec.generator, "generator")
        assertEquals(jce.order, nistP256Spec.order, "order")
        assertEquals(jce.cofactor, nistP256Spec.cofactor, "cofactor")
    }
}
