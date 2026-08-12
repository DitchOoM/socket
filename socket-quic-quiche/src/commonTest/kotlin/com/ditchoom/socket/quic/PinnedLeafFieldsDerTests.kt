@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit vectors for the shared DER leaf-field extractor ([parsePinnedLeafFieldsDer]) — positives from
 * real certificates, negatives from hand-assembled hostile DER. Runs on **every** target, which is the
 * only automated coverage the parser gets on iOS: `AppleQuicCertificateHashPinningTests` needs the
 * generated `pinned*` fixtures and therefore skips on a simulator (see
 * `AppleTestCerts.skippingWhenSimulatorLacksFixtures`).
 *
 * The positive vectors are **frozen hex**, deliberately not read from the `testcerts` directory: the
 * `generatePinnedW3cCerts` task remints those whenever `pinned` has under 3 days of validity left, so
 * their bytes — and their validity windows — are time-varying. A frozen copy asserts exact field values
 * forever; whether such a certificate is *currently* valid is [checkServerCertificatePinConstraints]'s
 * question, covered by `ServerCertificatePinConstraintsTests`, not this one.
 *
 * Agreement with a reference X.509 parser over a real corpus is asserted separately, by
 * `PinnedLeafFieldsDerDifferentialTest` (vs `java.security`, over 121 Mozilla roots + 6 fixtures).
 */
class PinnedLeafFieldsDerTests {
    // --- positives: real certificates, bytes frozen at the time this test was written ---

    /** `testcerts/pinned.crt` — the compliant W3C fixture: v3, EC P-256, 13-day validity, UTCTime. */
    @Test
    fun parsesV3EcP256Leaf() {
        val fields = assertNotNull(parse(EC_P256_LEAF), "v3 EC P-256 leaf should parse")
        assertEquals(Instant.parse("2026-08-11T20:44:57Z"), fields.notBefore)
        assertEquals(Instant.parse("2026-08-24T20:44:57Z"), fields.notAfter)
        assertTrue(fields.isEcP256, "pinned.crt is secp256r1")
        assertEquals("1.2.840.10045.3.1.7", fields.keyDescription, "EC keys are described by their curve OID")
    }

    /** `testcerts/pinned-rsa.crt` — v3, RSA-2048: parses fine, but is not a P-256 key. */
    @Test
    fun parsesV3RsaLeafAsNonP256() {
        val fields = assertNotNull(parse(RSA_LEAF), "v3 RSA leaf should parse")
        assertEquals(Instant.parse("2026-08-11T20:44:59Z"), fields.notBefore)
        assertEquals(Instant.parse("2026-08-24T20:44:59Z"), fields.notAfter)
        assertFalse(fields.isEcP256, "an RSA key is never P-256")
        assertEquals("1.2.840.113549.1.1.1", fields.keyDescription, "non-EC keys report the algorithm OID")
    }

    /**
     * `testcerts/cert.crt` — X.509 **v1**, so TBSCertificate has no `[0] EXPLICIT version` and every
     * later field sits one slot earlier. Getting this wrong reads the serial number as the validity.
     */
    @Test
    fun parsesV1LeafWithNoVersionTag() {
        val fields = assertNotNull(parse(V1_LEAF), "v1 leaf should parse")
        assertEquals(Instant.parse("2020-03-23T16:07:54Z"), fields.notBefore)
        assertEquals(Instant.parse("2047-08-09T16:07:54Z"), fields.notAfter)
        assertFalse(fields.isEcP256)
    }

    /**
     * The one certificate in the vendored 121-root Mozilla corpus that encodes both validity fields as
     * GeneralizedTime (`0x18`) rather than UTCTime — Certum Trusted Network CA 2. Its `notAfter` is in
     * 2046, past the UTCTime century pivot, which is exactly why it uses the wider type.
     */
    @Test
    fun parsesGeneralizedTimeValidity() {
        val fields = assertNotNull(parse(GENERALIZED_TIME_CA), "GeneralizedTime cert should parse")
        assertEquals(Instant.parse("2011-10-06T08:39:56Z"), fields.notBefore)
        assertEquals(Instant.parse("2046-10-06T08:39:56Z"), fields.notAfter)
    }

    /** RFC 5280 4.1.2.5.1's century pivot: UTCTime `YY` ≥ 50 is 19YY, below it is 20YY. */
    @Test
    fun appliesUtcTimeCenturyPivot() {
        val below = assertNotNull(parse(certValidFrom("490101000000Z", "490102000000Z")))
        assertEquals(Instant.parse("2049-01-01T00:00:00Z"), below.notBefore)
        val atPivot = assertNotNull(parse(certValidFrom("500101000000Z", "500102000000Z")))
        assertEquals(Instant.parse("1950-01-01T00:00:00Z"), atPivot.notBefore)
    }

    /**
     * The contract is "read absolutely over `[position(), limit())`" — the verifier hands over a buffer
     * whose limit is the DER length and whose position it rewound. Prove a non-zero start is honoured
     * and that parsing does not consume the buffer.
     */
    @Test
    fun readsFromTheBuffersPositionNotIndexZero() {
        val prefix = 7
        val buf = BufferFactory.deterministic().allocate(prefix + EC_P256_LEAF.size)
        repeat(prefix) { buf.writeByte(0xEE.toByte()) }
        EC_P256_LEAF.forEach { buf.writeByte(it) }
        buf.resetForRead()
        buf.position(prefix)
        val fields = assertNotNull(parsePinnedLeafFieldsDer(buf), "should parse from position()")
        assertEquals(Instant.parse("2026-08-11T20:44:57Z"), fields.notBefore)
        assertEquals(prefix, buf.position(), "parsing must not consume the buffer")
    }

    // --- negatives: every one must be a quiet null (fail-closed), never a throw ---

    @Test
    fun rejectsEmptyAndTruncatedInput() {
        assertNull(parse(byteArrayOf()), "empty")
        assertNull(parse(byteArrayOf(0x30)), "tag with no length octet")
        assertNull(parse(EC_P256_LEAF.copyOf(EC_P256_LEAF.size - 1)), "one byte short")
        assertNull(parse(EC_P256_LEAF.copyOf(40)), "truncated mid-TBSCertificate")
    }

    @Test
    fun rejectsIndefiniteAndReservedLengths() {
        // 0x80 is BER's indefinite length (forbidden in DER); 0xFF is reserved. Either would otherwise
        // send a length-driven walk off the end of the buffer.
        assertNull(parse(hex("30803000000000")), "indefinite length")
        assertNull(parse(hex("30ff3000")), "reserved length octet")
    }

    @Test
    fun rejectsOversizedAndOverflowingLengths() {
        // 5 length octets cannot fit an Int at all...
        assertNull(parse(hex("308501000000003000")), "5-octet length")
        // ...and 4 octets with the top bit set is a negative Int — the classic "length wraps past the
        // bound" trick. Bounds are checked by subtraction, so this is a plain reject, not a pass.
        assertNull(parse(hex("3084ffffffff3000")), "length that overflows Int")
        // A well-formed length that simply exceeds the buffer must not read past the limit either.
        assertNull(parse(hex("307f3000")), "length past the limit")
    }

    @Test
    fun rejectsHighTagNumberForm() {
        // Low 5 bits all set = multi-byte tag. Nothing in a leaf's skeleton uses it, and decoding it
        // would mean an unbounded continuation loop.
        assertNull(parse(hex("1f010200")), "high-tag-number form")
        val patched = EC_P256_LEAF.copyOf()
        patched[0] = 0x3F // constructed, high-tag-number
        assertNull(parse(patched), "high-tag-number outer tag")
    }

    @Test
    fun rejectsMalformedUtcTimes() {
        assertNull(parse(certValidFrom("260811204457+")), "not Z-terminated")
        assertNull(parse(certValidFrom("2608112044Z")), "11-byte UTCTime")
        assertNull(parse(certValidFrom("26081120445Z")), "12-byte UTCTime")
        assertNull(parse(certValidFrom("2608112044570Z")), "14-byte UTCTime")
        assertNull(parse(certValidFrom("26X811204457Z")), "non-digit in the body")
        assertNull(parse(certValidFrom("XY0811204457Z")), "non-digit year")
    }

    @Test
    fun rejectsMalformedGeneralizedTimes() {
        val short = cert(validity(generalizedTime("2026081120445Z"), generalizedTime("20260824204457Z")))
        assertNull(parse(short), "14-byte GeneralizedTime")
        val notZ = cert(validity(generalizedTime("20260811204457+"), generalizedTime("20260824204457Z")))
        assertNull(parse(notZ), "not Z-terminated")
        val good = cert(validity(generalizedTime("20260811204457Z"), generalizedTime("20260824204457Z")))
        assertEquals(Instant.parse("2026-08-11T20:44:57Z"), assertNotNull(parse(good)).notBefore)
    }

    /**
     * Calendar validity is the stdlib's question ([Instant.parseOrNull]) — which is why there is not a
     * line of leap-year arithmetic in the parser. These prove the delegation actually rejects.
     */
    @Test
    fun rejectsImpossibleCalendarDates() {
        assertNull(parse(certValidFrom("260230000000Z")), "Feb 30")
        assertNull(parse(certValidFrom("261301000000Z")), "month 13")
        assertNull(parse(certValidFrom("260000000000Z")), "month 0")
        assertNull(parse(certValidFrom("260811254457Z")), "hour 25")
        assertNull(parse(certValidFrom("260811206057Z")), "minute 60")
        assertNull(parse(certValidFrom("260229000000Z")), "Feb 29 of the non-leap year 2026")
        assertNotNull(parse(certValidFrom("280229000000Z", "280301000000Z")), "Feb 29 of the leap year 2028 is real")
    }

    @Test
    fun rejectsWrongTagWhereATimeBelongs() {
        assertNull(parse(cert(validity(der(0x01, byteArrayOf(0xFF.toByte())), utcTime("260824204457Z")))), "BOOLEAN notBefore")
        assertNull(parse(cert(validity(utcTime("260811204457Z"), der(0x02, byteArrayOf(1))))), "INTEGER notAfter")
    }

    /**
     * An EC key whose AlgorithmIdentifier carries explicit domain parameters (a `specifiedCurve`
     * SEQUENCE) instead of a namedCurve OID. That is not a *parse* failure — the certificate is
     * well-formed — so it must report `isEcP256 = false` and let
     * [checkServerCertificatePinConstraints] issue the `UnsupportedPublicKey` verdict.
     */
    @Test
    fun reportsSpecifiedCurveEcKeyAsNotP256() {
        val specifiedCurve = der(0x30, hex("020101")) // stand-in for ECParameters: structurally a SEQUENCE
        val spki = spki(hex(OID_EC_PUBLIC_KEY_DER) + specifiedCurve)
        val fields = assertNotNull(parse(cert(defaultValidity(), spki)), "specifiedCurve must still parse")
        assertFalse(fields.isEcP256, "an explicitly-parameterised curve is not the named P-256")
        assertEquals("1.2.840.10045.2.1", fields.keyDescription, "falls back to the algorithm OID")
    }

    /** A namedCurve that is not P-256 (secp384r1) parses, reports that curve, and is not P-256. */
    @Test
    fun reportsNonP256NamedCurve() {
        val spki = spki(hex(OID_EC_PUBLIC_KEY_DER) + hex("06052b81040022"))
        val fields = assertNotNull(parse(cert(defaultValidity(), spki)))
        assertFalse(fields.isEcP256)
        assertEquals("1.3.132.0.34", fields.keyDescription)
    }

    @Test
    fun rejectsMalformedObjectIdentifiers() {
        assertNull(parse(cert(defaultValidity(), spki(der(0x06, byteArrayOf())))), "empty OID")
        assertNull(parse(cert(defaultValidity(), spki(hex("06032a8180")))), "trailing continuation bit")
        assertNull(parse(cert(defaultValidity(), spki(hex("0603802a01")))), "non-minimal leading 0x80")
        assertNull(parse(cert(defaultValidity(), spki(hex("02022a01")))), "INTEGER where the algorithm OID belongs")
    }

    @Test
    fun rejectsNonSequenceOuterStructures() {
        assertNull(parse(der(0x31, hex("3000"))), "SET as the outer Certificate")
        assertNull(parse(der(0x30, der(0x31, hex("020101")))), "SET as the TBSCertificate")
        assertNull(parse(cert(der(0x31, utcTime("260101000000Z"), utcTime("260108000000Z")))), "SET as Validity")
    }

    /** A TBSCertificate that runs out of fields before Validity must be a null, not a throw. */
    @Test
    fun rejectsTruncatedFieldSequence() {
        val tbs = der(0x30, hex("a003020102"), der(0x02, byteArrayOf(1)))
        assertNull(parse(der(0x30, tbs)), "no signature/issuer/validity")
    }

    // --- helpers (tests may use ByteArray freely; production code may not) ---

    private fun parse(bytes: ByteArray) = parsePinnedLeafFieldsDer(buffer(bytes))

    private fun buffer(bytes: ByteArray): ReadBuffer {
        val buf = BufferFactory.deterministic().allocate(maxOf(bytes.size, 1))
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        buf.setLimit(bytes.size)
        return buf
    }

    /** Definite-length DER encode of [tag] over the concatenated [content]. */
    private fun der(
        tag: Int,
        vararg content: ByteArray,
    ): ByteArray {
        val body = content.fold(ByteArray(0)) { acc, b -> acc + b }
        val header =
            when {
                body.size < 0x80 -> byteArrayOf(tag.toByte(), body.size.toByte())
                body.size < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), body.size.toByte())
                else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (body.size ushr 8).toByte(), body.size.toByte())
            }
        return header + body
    }

    private fun utcTime(value: String) = der(0x17, value.encodeToByteArray())

    private fun generalizedTime(value: String) = der(0x18, value.encodeToByteArray())

    private fun validity(
        notBefore: ByteArray,
        notAfter: ByteArray,
    ) = der(0x30, notBefore, notAfter)

    private fun defaultValidity() = validity(utcTime("260101000000Z"), utcTime("260108000000Z"))

    /** SubjectPublicKeyInfo whose AlgorithmIdentifier content is [algorithm]; the BIT STRING is a stub. */
    private fun spki(algorithm: ByteArray) = der(0x30, der(0x30, algorithm), der(0x03, byteArrayOf(0x00, 0x04)))

    private fun ecP256Spki() = spki(hex(OID_EC_PUBLIC_KEY_DER) + hex("06082a8648ce3d030107"))

    /** A minimal but structurally faithful v3 Certificate carrying [validity] and [subjectPublicKeyInfo]. */
    private fun cert(
        validity: ByteArray,
        subjectPublicKeyInfo: ByteArray = ecP256Spki(),
    ): ByteArray {
        val version = hex("a003020102")
        val serial = der(0x02, byteArrayOf(1))
        val signatureAlgorithm = der(0x30, hex("06082a8648ce3d040302")) // ecdsa-with-SHA256
        val emptyName = der(0x30)
        val tbs = der(0x30, version, serial, signatureAlgorithm, emptyName, validity, emptyName, subjectPublicKeyInfo)
        return der(0x30, tbs, signatureAlgorithm, der(0x03, byteArrayOf(0x00, 0x00)))
    }

    private fun certValidFrom(
        notBefore: String,
        notAfter: String = "260824204457Z",
    ) = cert(validity(utcTime(notBefore), utcTime(notAfter)))

    private fun hex(value: String) =
        ByteArray(value.length / 2) { ((value[it * 2].digitToInt(16) shl 4) or value[it * 2 + 1].digitToInt(16)).toByte() }

    private companion object {
        /** DER of the id-ecPublicKey OBJECT IDENTIFIER (1.2.840.10045.2.1), tag + length included. */
        const val OID_EC_PUBLIC_KEY_DER = "06072a8648ce3d0201"

        private fun frozen(value: String) =
            ByteArray(value.length / 2) { ((value[it * 2].digitToInt(16) shl 4) or value[it * 2 + 1].digitToInt(16)).toByte() }

        /** `testcerts/pinned.crt` (v3, EC P-256, 13-day, UTCTime), frozen. */
        val EC_P256_LEAF =
            frozen(
                "3082017030820116a00302010202081ff96678d40f9c7e300a06082a8648ce3d04030230143112301006035504031309" +
                    "6c6f63616c686f7374301e170d3236303831313230343435375a170d3236303832343230343435375a30143112301006" +
                    "0355040313096c6f63616c686f73743059301306072a8648ce3d020106082a8648ce3d0301070342000460e5db991b77" +
                    "f7d63e85660e06f4ebe7e71e6b95d22e3670fd9fd5877795ccb310001acd412b616fa0fc4febb95e84f8f29ac4cda885" +
                    "80a070603b36f0387261a3523050301d0603551d0e04160414b310f57a01b639867ebcd4f9eae95564a7b7d049301a06" +
                    "03551d110413301182096c6f63616c686f737487047f00000130130603551d25040c300a06082b06010505070301300a" +
                    "06082a8648ce3d04030203480030450221009968f84214c18c7a5ca7a9c4528b68de1454bc62398fa8481ca2a245a03e" +
                    "1d4e02206ef66456f27ec82a43fbb8dd9a4b8014b3f85f4018d872fd36fbe61d770a44e9",
            )

        /** `testcerts/pinned-rsa.crt` (v3, RSA-2048), frozen. */
        val RSA_LEAF =
            frozen(
                "308202fd308201e5a003020102020900df752535d1225781300d06092a864886f70d01010b0500301431123010060355" +
                    "040313096c6f63616c686f7374301e170d3236303831313230343435395a170d3236303832343230343435395a301431" +
                    "123010060355040313096c6f63616c686f737430820122300d06092a864886f70d01010105000382010f003082010a02" +
                    "82010100f693c33cc30cc3c685b081258c9e586730d3a3c4dda454ec30970c955aa61b5d227ac5761e36d17b21864666" +
                    "fee430d1b199aea37aa49a394aca0d75993c43624120eb262e5c18435097230dc343eb1cc5270fdaed4ff5cf2c31c39b" +
                    "6acd27899f05ba3210354129832f6380524675f0cdcb5ca77c604d46f5d139cc58c90a45e6691d5cdd4d6e13f7ef9120" +
                    "db677dc38a84e767c6c753f4d7e54bdb9bc7dea646c25bf92df28f09cf2b5e8deed09bfefa35b9cb4d08315902b8e53a" +
                    "bf187751ebb24e033ac376f88c70198bc28dbe541770fe3e0812d03134441f74d34c46916e92f88cb4dfa2de5fd86d42" +
                    "3bd09a72673d6ccf4321f744cc81fedd8f9b71330203010001a3523050301d0603551d0e041604146f77fd7c23ac681b" +
                    "9f50b4028af490bb9862a2a8301a0603551d110413301182096c6f63616c686f737487047f00000130130603551d2504" +
                    "0c300a06082b06010505070301300d06092a864886f70d01010b050003820101008f991929cf3146021613595da1fd6c" +
                    "1df862280363230e01e4c6f971f30441a7b34155058d8cd91bdd27c508879309581c7610e8c5bd24acd25ab959e00dea" +
                    "7cf0efc8cf85291d698598cd651dbfbde92a898604a132f91439b4e4375bad4727e6f81f8ae77aced4ac74304a903958" +
                    "1617b3b3f4edd01d54e7c4a773cee3333c7d6275f395ac87f2ed27c2d35904b2923390be73425bffd757214fcd507b20" +
                    "a54a06ee1df9c20fadca4c246b98a4b0a611e970b81482d97127395b3e47b2228f0bab7227664d4c3692d3d07e3e76d2" +
                    "8810e90a8f5f6ea03a4d197315b518f5236a172d7c546fd0a83b31c6995b3ae217ee50dbbcf6c7f5038e28744be7bbaa" +
                    "31",
            )

        /** `testcerts/cert.crt` — X.509 v1 (no `[0] EXPLICIT version`), frozen. */
        val V1_LEAF =
            frozen(
                "308202ed308201d502143b8606197797967ef9508b92f69abf8cad781a27300d06092a864886f70d01010b0500304531" +
                    "0b30090603550406130241553113301106035504080c0a536f6d652d53746174653121301f060355040a0c18496e7465" +
                    "726e6574205769646769747320507479204c7464301e170d3230303332333136303735345a170d343730383039313630" +
                    "3735345a3021310b30090603550406130247423112301006035504030c09717569632e7465636830820122300d06092a" +
                    "864886f70d01010105000382010f003082010a0282010100cf96ce2fb2c3f648886a0715ad9a99d7765c4742a132ecec" +
                    "6eba942db64acaa0beb8146020dc5827b2cf9c9e0b3d8b82b7f9c0690ec22d77ca83300c16ef1e20ae9412f640ebeedb" +
                    "db4138aef3a93db4c1fd3e096d864d40ac8cf40130afa8f43fabc582b593eda0738649a2a847b9befffb64e1066fe01b" +
                    "f9cbcc7accdf05b93dde7cb329d35a52e87de71effa743b0df5e67a763d1ba84f44109c0f7313ff5e67725a87770d667" +
                    "36e43a0431e591e8334d5e4985861b951a3fa66b7613d13485af8d5ac44b7f7649518864611dd4a8c96d10ab8b82508e" +
                    "55605b3e629de08654348a2da4a31541255bf5a80d045e3d847f620613777c19bb1e9f0a0698c92f0203010001300d06" +
                    "092a864886f70d01010b05000382010100a8fd19f8b296b95c5934702a0aca93f6ec3c5e3e4183f6b41993f098e229d5" +
                    "255d7a6a7675a6bf727ceeb5fa6ddf44a4d9abbcb1b474435b10dd44e1f1f4ba95d5d5cb02687579c57d2a7ebfefde8e" +
                    "107b0e7150742df8bd2258bbfffa14aa584636996846e41b81601753e128d712515885de79d87305280438c690937667" +
                    "4dd60584fd3f028fdf11d2385542efd40e37ced9d9201d8a5d6814428153df6c282f8ede5a07bc2f1c559a61f7493b73" +
                    "9cd7069d8c48a2b0907a60c70f332bbf1456807c64a45186a80864145c829a12853e094ac2dfb2553c76e93f2d4a1203" +
                    "212323d2b815329b66b562897f3342bc51b9da0b2e3b8c37c86763823451ac10e6",
            )

        /**
         * Certum Trusted Network CA 2, from the vendored `mozilla-ca/cacert.pem` — the one root of 121
         * whose validity is GeneralizedTime (`0x18`) rather than UTCTime. Frozen.
         */
        val GENERALIZED_TIME_CA =
            frozen(
                "308205d2308203baa003020102021021d6d04a4f250fc93237fcaa5e128de9300d06092a864886f70d01010d05003081" +
                    "80310b300906035504061302504c31223020060355040a1319556e697a65746f20546563686e6f6c6f6769657320532e" +
                    "412e31273025060355040b131e43657274756d2043657274696669636174696f6e20417574686f726974793124302206" +
                    "03550403131b43657274756d2054727573746564204e6574776f726b20434120323022180f3230313131303036303833" +
                    "3935365a180f32303436313030363038333935365a308180310b300906035504061302504c31223020060355040a1319" +
                    "556e697a65746f20546563686e6f6c6f6769657320532e412e31273025060355040b131e43657274756d204365727469" +
                    "6669636174696f6e20417574686f72697479312430220603550403131b43657274756d2054727573746564204e657477" +
                    "6f726b204341203230820222300d06092a864886f70d01010105000382020f003082020a0282020100bdf978f8e6d580" +
                    "0c649d861b9664673f223a1e75017deffb5c678cc9cc5c6ba991e6b942e5204b9bda9b7bb9995dd99b804bd784402b27" +
                    "d3e8ba30bb3e091aa74995ef2b4024c297c7a7ee9b25efa80a0097855aaa9ddc29c9e23507eb704d4ad6c1b356b8a141" +
                    "389bd1fb317f8fe05fe1b13f0f8e164960d7068d18f9aa2610ab2ad3d0d1678d1b46be4730d52e72d1c563dae7637944" +
                    "7e4b632489862e343f294c528b2aa7c0e2912889b9c05bf91dd9e727adff9a0297c1c650929b022cbda9b934590abf84" +
                    "4affdffeb39febd99ee09823eca66b77162adbccad3b1ca487dc46735e1962684557e4908242bb42d6f061e0c1a33d66" +
                    "a35df418ee88c98d1745299932750231ee2926c86b02e6b562457f37155a236889d43ede4e27b0f0400cbc4d17cb4da2" +
                    "b31ed0065addf693cf577599f5fa861a6778b3bf96fe34dcbde75256e5b3e5757bd7419105dc5d69e3950d43b9fc8396" +
                    "39957b6c805a4f1372c6d77d297a44ba52a42ad541460920fe22a0b65b308dbc890cd5d770f88752fddaefac512e07b3" +
                    "4efed009da70ef98fa56e66ddbb5574bdce52c2515c89e2e784ef8da9c9e862cca57f31ae5c8928b1a82967ac3bc5012" +
                    "69d80e5a468b3aeb26fa23c9b6b081be4200a4f8d6fe302ec7d246f6e58e75fdf2ccb9d0875bcc061060bb8335b75e67" +
                    "de47ec9948f1a4a115fead8c628e39554f3916b9b1639dffb70203010001a3423040300f0603551d130101ff04053003" +
                    "0101ff301d0603551d0e04160414b6a1543902c3a03f8e8abcfad4f81ca6d13a0efd300e0603551d0f0101ff04040302" +
                    "0106300d06092a864886f70d01010d0500038202010071a50ecee4e9bf3f38d5895ac40261fb4cc514172d8b4f536b10" +
                    "17fc6584c7104990dedbc7269388266f70d6025e39a0f78fab96b5a5135c81146d0e8182111b8a4ec64fa5dd621e44df" +
                    "0959f45b770b37e98b20c6f80a4e2e581ceb33d0cf8660c9dafb802f9e4c6084783d2164d6fb411f180fe7c97571bdbd" +
                    "5cde34873e41b00ef6b9d63f091396142fde9a1d5ab956ce353ab05f704d5ee329f123287259b6abc28c66261c772c26" +
                    "76358b28a769a0f93bf523dd851074c990035691e7afba47d412971122e3a249946ce7b7944bba2da4da338b4ca644ff" +
                    "5a3cc61d64d8b531e4a63c7aa8570bdbed611acbf1ce737763a4876f4c5138d6e45fc79fb6812ae4854879585e3bf8db" +
                    "028267c139dbc3744b3d361ef9299388685ba8441921f0a7e8810d2ce89336b437b2cab01b267a9a251f9a9a809e4b2a" +
                    "3ffba39afe733271c29ec672e18a6827f1e40fb4c44ca56193f89710072a3025a9b9c871b8ef68cc2d7ef5e07e0f82a8" +
                    "6fb6ba6c834377cd8a9217a19e5b78163d45e23372dde166ca99d3c9c526fd0d680446aeb6d99b8cbe19beb1c6f219e3" +
                    "5c02ca2cd86f4a07d9c935da4075f2c4a7196f9e42109875e6958b60bcedc512d78aced5985c569603c5ee770635ffcf" +
                    "e4ee3f1361eedbda2d85f0cdae9db2180945c392a17217fc47b6a00b2cf1c4de4368086a5f3bf07663fbcc062ca6c6e2" +
                    "0eb5b9be248f",
            )
    }
}
