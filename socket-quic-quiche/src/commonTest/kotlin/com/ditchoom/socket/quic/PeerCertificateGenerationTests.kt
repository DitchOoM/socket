@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Certificates minted by this platform's engine ([peerCertificates]), read back by the DER walk the
 * client's `serverCertificateHashes` check uses ([parsePinnedLeafFieldsDer]) and judged by the shared W3C
 * policy ([checkServerCertificatePinConstraints]). Runs on every quiche target, including the iOS
 * simulator; `java.security` and Security.framework parse the same output in the JVM and Apple tests.
 */
class PeerCertificateGenerationTests {
    private fun engine(): PeerCertificateSupport.Available =
        when (val support = peerCertificates) {
            is PeerCertificateSupport.Available -> support
            PeerCertificateSupport.Unavailable -> fail("peerCertificates=$support on a quiche target")
        }

    @Test
    fun defaultCertificateMeetsTheW3cConstraintsNow() {
        val before = Clock.System.now()
        engine().generate().use { certificate ->
            val der = certificate.der()
            val fields = assertNotNull(parsePinnedLeafFieldsDer(der), "DER walk could not parse ${der.toHexString()}")
            assertEquals(certificate.notBefore, fields.notBefore)
            assertEquals(certificate.notAfter, fields.notAfter)
            assertTrue(fields.isEcP256, "key is ${fields.keyDescription}")
            assertEquals(14.days, fields.notAfter - fields.notBefore)
            assertNull(checkServerCertificatePinConstraints(fields, Clock.System.now()), "constraints rejected ${der.toHexString()}")
            // Backdated by the skew allowance, to the second.
            val backdate = before - certificate.notBefore
            assertTrue(
                backdate >= PEER_CERTIFICATE_CLOCK_SKEW_ALLOWANCE && backdate < PEER_CERTIFICATE_CLOCK_SKEW_ALLOWANCE + 1.minutes,
                "backdate=$backdate",
            )
        }
    }

    @Test
    fun explicitWindowIsEncodedToTheSecond() {
        val start = Instant.parse("2031-02-03T04:05:06.789Z")
        val validity = (PeerCertificateValidity.of(90.minutes) as PeerCertificateValidity.Result.Valid).validity
        engine().generate(validity, start).use { certificate ->
            val fields = assertNotNull(parsePinnedLeafFieldsDer(certificate.der()))
            assertEquals(Instant.parse("2031-02-03T04:05:06Z"), fields.notBefore)
            assertEquals(Instant.parse("2031-02-03T05:35:06Z"), fields.notAfter)
            assertEquals(certificate.notAfter, fields.notAfter)
            assertEquals(Instant.parse("2031-02-03T05:05:06Z"), certificate.renewAt(30.minutes))
        }
    }

    @Test
    fun windowAfter2049UsesGeneralizedTime() {
        engine().generate(PeerCertificateValidity.Maximum, Instant.parse("2049-12-25T00:00:00Z")).use { certificate ->
            val fields = assertNotNull(parsePinnedLeafFieldsDer(certificate.der()))
            assertEquals(Instant.parse("2049-12-25T00:00:00Z"), fields.notBefore)
            assertEquals(Instant.parse("2050-01-08T00:00:00Z"), fields.notAfter)
        }
    }

    @Test
    fun everyCertificateHasAFreshKey() {
        engine().generate().use { first ->
            engine().generate().use { second ->
                assertNotEquals(first.hash, second.hash)
                assertNotEquals(first.der().toHexString(), second.der().toHexString())
            }
        }
    }

    @Test
    fun expiredWindowIsRejectedByThePolicy() {
        engine().generate(notBefore = Clock.System.now() - 20.days).use { certificate ->
            val fields = assertNotNull(parsePinnedLeafFieldsDer(certificate.der()))
            val failure = checkServerCertificatePinConstraints(fields, Clock.System.now())
            assertTrue(failure is com.ditchoom.socket.CertificateHashPinningFailure.NotTemporallyValid, "got $failure")
            assertTrue(certificate.renewAt(1.hours) < Clock.System.now())
        }
    }
}
