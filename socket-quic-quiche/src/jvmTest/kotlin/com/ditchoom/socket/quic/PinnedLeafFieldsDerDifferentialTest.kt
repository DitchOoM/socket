@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The load-bearing correctness evidence for the hand-rolled DER walk in [parsePinnedLeafFieldsDer]:
 * field-for-field agreement with `java.security` ([parsePinnedLeafFieldsJvm]) — a battle-tested
 * reference X.509 parser — over a real corpus of **127** certificates: the 121 vendored Mozilla NSS
 * roots (`mozilla-ca/cacert.pem`, the bundle already shipped for iOS `verifyPeer=true` trust) plus the
 * 6 generated `testcerts` fixtures.
 *
 * This is the same evidence standard the repo already applies to its other hand-rolled codec (the
 * HTTP/3 + QPACK encoder/decoder, differentially fuzzed against a reference). It is **mandatory**, not
 * optional: without it, a structural ASN.1 walk has no argument for existing next to a real parser.
 * A mismatch here means the walk is wrong on a real-world certificate shape — treat it as a stop-ship
 * for the Apple constraint enforcement this parser backs, not as a test to relax.
 *
 * **What the corpus does and does not cover.** All 127 entries are self-signed roots or OpenSSL-minted
 * fixtures, and every one of them encodes its validity as a seconds-precision `Z`-terminated UTCTime or
 * GeneralizedTime and its key as a namedCurve EC or an RSA key. So "agreement over the corpus" is a
 * statement about those shapes, not about X.509 in general — and in particular the corpus contains
 * **none** of the three shapes where the two parsers are known to diverge. Those are pinned explicitly
 * below (`divergesFrom…`) as frozen-hex vectors asserting what each parser *actually* does, so a change
 * on either side fails a test instead of surfacing as a field mismatch on some future corpus entry.
 *
 * Runs on the JVM only (that is where the reference parser lives); the corpus is platform-independent,
 * so the same bytes exercised here are what every other target runs through the identical commonMain
 * code. `AppleMozillaCaDerParseTest` re-walks the same 121 roots on macOS **and** the iOS simulator to
 * prove the K/N compilation of it behaves, and `PinnedLeafFieldsDerTests` pins frozen vectors everywhere.
 */
class PinnedLeafFieldsDerDifferentialTest {
    /**
     * Comparisons that ran all three field assertions to completion, counted at the *end* of
     * [assertAgrees]. A differential test and a vacuous one look identical from a green tick, so the
     * count is asserted, not just printed: an empty corpus, a glob that matched nothing, or a
     * `forEach` over a list that silently lost entries all fail here instead of passing quietly.
     */
    private var comparisons = 0

    /** Corpus entries the reference reported as EC P-256, so the boolean field is not trivially all-false. */
    private var ecP256Seen = 0

    @Test
    fun agreesWithJavaSecurityOverTheMozillaRootCorpus() {
        val certs = mozillaRootDers()
        assertTrue(certs.size >= 100, "expected the vendored Mozilla bundle, found ${certs.size} certificates")
        certs.forEachIndexed { index, der -> assertAgrees("mozilla-ca/cacert.pem[$index]", der) }
        assertEquals(certs.size, comparisons, "every parsed root must have been compared field-for-field")
        assertTrue(comparisons >= 100, "only $comparisons comparisons ran — the corpus did not load")
        assertTrue(ecP256Seen > 0, "no EC P-256 root in the corpus: the isEcP256 comparison would be vacuously all-false")
        println("PinnedLeafFieldsDerDifferentialTest: $comparisons Mozilla roots compared ($ecP256Seen EC P-256), 0 mismatches")
    }

    @Test
    fun agreesWithJavaSecurityOverTheGeneratedFixtures() {
        val names = listOf("cert.crt", "localhost.crt", "pinned.crt", "pinned-expired.crt", "pinned-toolong.crt", "pinned-rsa.crt")
        names.forEach { name -> assertAgrees("certs/$name", pemToDer(fixturePem(name))) }
        assertEquals(names.size, comparisons, "every generated fixture must have been compared field-for-field")
        assertTrue(ecP256Seen > 0, "no EC P-256 fixture: the isEcP256 comparison would be vacuously all-false")
        println("PinnedLeafFieldsDerDifferentialTest: $comparisons generated fixtures compared ($ecP256Seen EC P-256), 0 mismatches")
    }

    // --- the three known divergences, pinned as explicit vectors ---------------------------------
    //
    // Each is a real certificate (OpenSSL-minted, then — for the two time shapes — its Validity TLVs
    // rewritten and the enclosing lengths recomputed, since no tool emits them). They assert the ACTUAL
    // behaviour of both parsers, not the desired one. All three end in a rejected connection on the
    // walk's backends; what differs is which typed CertificateHashPinningFailure the caller sees, so
    // none of them is a hole through which a certificate is wrongly accepted.

    /**
     * **(a) EC key with explicit (`specifiedCurve`) domain parameters** instead of a namedCurve OID
     * (`openssl ecparam -param_enc explicit`). Here it is `java.security` that is the stricter one: it
     * refuses the certificate outright ("Only named ECParameters supported"), so the JVM backend
     * reports `CertificateParseFailed`. The shared walk parses the skeleton fine, sees a SEQUENCE where
     * the namedCurve OID would be, and reports `isEcP256 = false` with the algorithm OID — so its
     * backends reject with `UnsupportedPublicKey` instead. Both reject; the failure type differs.
     */
    @Test
    fun divergesFromJavaSecurityOnExplicitEcParameters() {
        assertNull(
            parsePinnedLeafFieldsJvm(buffer(EC_EXPLICIT_PARAMS_LEAF)),
            "java.security is expected to refuse a specifiedCurve EC certificate entirely",
        )
        val walked = assertNotNull(parsePinnedLeafFieldsDer(buffer(EC_EXPLICIT_PARAMS_LEAF)), "the walk parses it")
        assertEquals(Instant.parse("2026-08-11T20:44:57Z"), walked.notBefore)
        assertEquals(Instant.parse("2026-08-24T20:44:57Z"), walked.notAfter)
        assertFalse(walked.isEcP256, "an explicitly-parameterised curve is not the named P-256")
        assertEquals("1.2.840.10045.2.1", walked.keyDescription, "falls back to the id-ecPublicKey algorithm OID")
    }

    /**
     * **(b) UTCTime without seconds** — `YYMMDDHHMMZ`, 11 content octets. RFC 5280 requires seconds;
     * plain ASN.1 UTCTime does not. `java.security` accepts it and reads the seconds as `00`; the walk
     * requires exactly 13 octets (`YYMMDDHHMMSSZ`) and returns `null`, so its backends fail closed with
     * `CertificateParseFailed` rather than inventing a validity window.
     */
    @Test
    fun divergesFromJavaSecurityOnUtcTimeWithoutSeconds() {
        val reference = assertNotNull(parsePinnedLeafFieldsJvm(buffer(UTC_TIME_NO_SECONDS_LEAF)), "java.security accepts it")
        assertEquals(Instant.parse("2026-08-11T20:44:00Z"), reference.notBefore, "seconds default to 00")
        assertEquals(Instant.parse("2026-08-24T20:44:00Z"), reference.notAfter)
        assertTrue(reference.isEcP256, "the key is an ordinary namedCurve P-256 — only the time shape is unusual")
        assertNull(parsePinnedLeafFieldsDer(buffer(UTC_TIME_NO_SECONDS_LEAF)), "the walk requires seconds-precision UTCTime")
    }

    /**
     * **(c) GeneralizedTime with fractional seconds or a numeric UTC offset** — RFC 5280 forbids both
     * (`YYYYMMDDHHMMSSZ` only). `java.security` accepts them, keeping the fraction and normalising the
     * offset to UTC; the walk requires exactly 15 octets ending in `Z` and returns `null` for either,
     * so its backends fail closed with `CertificateParseFailed`.
     */
    @Test
    fun divergesFromJavaSecurityOnNonConformantGeneralizedTime() {
        val fractional = assertNotNull(parsePinnedLeafFieldsJvm(buffer(GENERALIZED_TIME_FRACTIONAL_LEAF)), "java.security accepts .500")
        assertEquals(Instant.parse("2026-08-11T20:44:57.500Z"), fractional.notBefore)
        assertEquals(Instant.parse("2026-08-24T20:44:57.500Z"), fractional.notAfter)
        assertNull(parsePinnedLeafFieldsDer(buffer(GENERALIZED_TIME_FRACTIONAL_LEAF)), "the walk rejects fractional seconds")

        val offset = assertNotNull(parsePinnedLeafFieldsJvm(buffer(GENERALIZED_TIME_OFFSET_LEAF)), "java.security accepts -0800")
        assertEquals(Instant.parse("2026-08-12T04:44:57Z"), offset.notBefore, "-0800 is normalised to UTC")
        assertEquals(Instant.parse("2026-08-25T04:44:57Z"), offset.notAfter)
        assertNull(
            parsePinnedLeafFieldsDer(buffer(GENERALIZED_TIME_OFFSET_LEAF)),
            // `…57-0800` is 19 content octets, so the exact-15 length check is what rejects it here; the
            // Z-terminator check backstops a 15-octet non-Z form. Either way: null.
            "the walk requires a 15-octet Z-terminated GeneralizedTime",
        )
    }

    /**
     * Both parsers must agree on every field the W3C constraints read. `keyDescription` is exempt by
     * design — it is an opaque per-platform diagnostic string (see [X509PinFields.keyDescription]), and
     * the shared walk reports the algorithm or curve OID where `java.security` reports a name like
     * `RSA-2048` or `EC 256-bit`. Nothing branches on it; [X509PinFields.isEcP256] carries the verdict.
     */
    private fun assertAgrees(
        label: String,
        der: ByteArray,
    ) {
        val reference = assertNotNull(parsePinnedLeafFieldsJvm(buffer(der)), "$label: java.security could not parse the corpus entry")
        val actual = assertNotNull(parsePinnedLeafFieldsDer(buffer(der)), "$label: the shared DER walk returned null")
        assertEquals(reference.notBefore, actual.notBefore, "$label: notBefore")
        assertEquals(reference.notAfter, actual.notAfter, "$label: notAfter")
        assertEquals(reference.isEcP256, actual.isEcP256, "$label: isEcP256 (reference key = ${reference.keyDescription})")
        if (reference.isEcP256) ecP256Seen++
        comparisons++
    }

    private fun buffer(bytes: ByteArray): ReadBuffer {
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return buf
    }

    private fun mozillaRootDers(): List<ByteArray> = PEM_BLOCK.findAll(mozillaCaPem().readText()).map { pemToDer(it.value) }.toList()

    /** The bundle is vendored in the module, so resolve it relative to the module dir (the test cwd). */
    private fun mozillaCaPem(): File {
        val candidates = listOf(File("mozilla-ca/cacert.pem"), File("socket-quic-quiche/mozilla-ca/cacert.pem"))
        return candidates.firstOrNull { it.isFile }
            ?: error("mozilla-ca/cacert.pem not found from ${File(".").absolutePath} — tried ${candidates.map { it.path }}")
    }

    private fun fixturePem(name: String): String =
        this::class.java.classLoader
            .getResourceAsStream("certs/$name")
            ?.use { it.readBytes().decodeToString() }
            ?: error("certs/$name not on the test classpath — did :socket-quic-quiche:generatePinnedW3cCerts run?")

    private fun pemToDer(pem: String): ByteArray {
        val body =
            pem
                .substringAfter("-----BEGIN CERTIFICATE-----")
                .substringBefore("-----END CERTIFICATE-----")
        return Base64.getMimeDecoder().decode(body)
    }

    private companion object {
        val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)

        private fun frozen(value: String) =
            ByteArray(value.length / 2) { ((value[it * 2].digitToInt(16) shl 4) or value[it * 2 + 1].digitToInt(16)).toByte() }

        /**
         * Self-signed EC certificate whose SubjectPublicKeyInfo carries **explicit** P-256 domain
         * parameters rather than the `prime256v1` OID. Minted with
         * `openssl ecparam -name prime256v1 -param_enc explicit -genkey` +
         * `openssl req -x509 -not_before 20260811204457Z -not_after 20260824204457Z`, then frozen.
         */
        val EC_EXPLICIT_PARAMS_LEAF =
            frozen(
                "3082027230820217a0030201020214428e4622ec7ae42ce6c81d1755b39b2d425cc226300a06082a8648ce3d040302301431" +
                    "12301006035504030c096c6f63616c686f7374301e170d3236303831313230343435375a170d323630383234323034343537" +
                    "5a30143112301006035504030c096c6f63616c686f73743082014b3082010306072a8648ce3d02013081f7020101302c0607" +
                    "2a8648ce3d0101022100ffffffff00000001000000000000000000000000ffffffffffffffffffffffff305b0420ffffffff" +
                    "00000001000000000000000000000000fffffffffffffffffffffffc04205ac635d8aa3a93e7b3ebbd55769886bc651d06b0" +
                    "cc53b0f63bce3c3e27d2604b031500c49d360886e704936a6678e1139d26b7819f7e900441046b17d1f2e12c4247f8bce6e5" +
                    "63a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf" +
                    "51f5022100ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63255102010103420004346bf5032cea" +
                    "f9512c00c24e3933858c57a27bec65cba70c5cbfd0124e6441eb31ff1e6dfae10331397bc13c6837783288a95ad07a7a7603" +
                    "9f28b5a0a82100d7a3533051301d0603551d0e04160414dcf8f7dc4d2ed08e65c88aaa2d7ee96988024a61301f0603551d23" +
                    "041830168014dcf8f7dc4d2ed08e65c88aaa2d7ee96988024a61300f0603551d130101ff040530030101ff300a06082a8648" +
                    "ce3d0403020349003046022100b36fa93af49a82a7496e7d985a7f255a6804ded7fac18fea43b50a2d0771ac20022100f0e6" +
                    "beae6ff005ae43461586e9dfd16951a76a2ee40295fe54c3bc478de280e7",
            )

        /**
         * The same OpenSSL-minted namedCurve P-256 leaf as [GENERALIZED_TIME_FRACTIONAL_LEAF] and
         * [GENERALIZED_TIME_OFFSET_LEAF], with its Validity rewritten to UTCTime `YYMMDDHHMMZ`
         * (11 octets, no seconds) and the enclosing SEQUENCE lengths recomputed. Frozen.
         */
        val UTC_TIME_NO_SECONDS_LEAF =
            frozen(
                "308201793082011fa0030201020214569b93bc0305e884d068d2db0fa74990ef436ff8300a06082a8648ce3d040302301431" +
                    "12301006035504030c096c6f63616c686f7374301a170b323630383131323034345a170b323630383234323034345a301431" +
                    "12301006035504030c096c6f63616c686f73743059301306072a8648ce3d020106082a8648ce3d030107034200044c995da6" +
                    "12aaf2badc838b0bfb709f488b32ba82260bfbf5b6de6b354946882558e18938f4236e00361b5e5f23bfab083e7e0a849b9e" +
                    "7c9b2386d114797ed933a3533051301d0603551d0e041604141e4ced5705c5538be1c6c8a790b801ef19c2fb9d301f060355" +
                    "1d230418301680141e4ced5705c5538be1c6c8a790b801ef19c2fb9d300f0603551d130101ff040530030101ff300a06082a" +
                    "8648ce3d04030203480030450221009a0e07a1b33741b9749d95d1a166069f4194e7f025fb43a66429929605e599af022012" +
                    "ae3db619f79d1427a1351f138e595aa021d5c488b9507b9c5469122ef49b9b",
            )

        /** As above, but Validity rewritten to GeneralizedTime with fractional seconds (`…57.500Z`). Frozen. */
        val GENERALIZED_TIME_FRACTIONAL_LEAF =
            frozen(
                "308201893082012fa0030201020214569b93bc0305e884d068d2db0fa74990ef436ff8300a06082a8648ce3d0403023014" +
                    "3112301006035504030c096c6f63616c686f7374302a181332303236303831313230343435372e3530305a1813323032" +
                    "36303832343230343435372e3530305a30143112301006035504030c096c6f63616c686f73743059301306072a8648ce" +
                    "3d020106082a8648ce3d030107034200044c995da612aaf2badc838b0bfb709f488b32ba82260bfbf5b6de6b35494688" +
                    "2558e18938f4236e00361b5e5f23bfab083e7e0a849b9e7c9b2386d114797ed933a3533051301d0603551d0e04160414" +
                    "1e4ced5705c5538be1c6c8a790b801ef19c2fb9d301f0603551d230418301680141e4ced5705c5538be1c6c8a790b801" +
                    "ef19c2fb9d300f0603551d130101ff040530030101ff300a06082a8648ce3d04030203480030450221009a0e07a1b337" +
                    "41b9749d95d1a166069f4194e7f025fb43a66429929605e599af022012ae3db619f79d1427a1351f138e595aa021d5c4" +
                    "88b9507b9c5469122ef49b9b",
            )

        /** As above, but Validity rewritten to GeneralizedTime with a numeric UTC offset (`…57-0800`). Frozen. */
        val GENERALIZED_TIME_OFFSET_LEAF =
            frozen(
                "308201893082012fa0030201020214569b93bc0305e884d068d2db0fa74990ef436ff8300a06082a8648ce3d0403023014" +
                    "3112301006035504030c096c6f63616c686f7374302a181332303236303831313230343435372d30383030181332303236" +
                    "303832343230343435372d30383030" +
                    "30143112301006035504030c096c6f63616c686f73743059301306072a8648ce3d020106082a8648ce3d030107034200" +
                    "044c995da612aaf2badc838b0bfb709f488b32ba82260bfbf5b6de6b354946882558e18938f4236e00361b5e5f23bfab" +
                    "083e7e0a849b9e7c9b2386d114797ed933a3533051301d0603551d0e041604141e4ced5705c5538be1c6c8a790b801ef" +
                    "19c2fb9d301f0603551d230418301680141e4ced5705c5538be1c6c8a790b801ef19c2fb9d300f0603551d130101ff04" +
                    "0530030101ff300a06082a8648ce3d04030203480030450221009a0e07a1b33741b9749d95d1a166069f4194e7f025fb" +
                    "43a66429929605e599af022012ae3db619f79d1427a1351f138e595aa021d5c488b9507b9c5469122ef49b9b",
            )
    }
}
