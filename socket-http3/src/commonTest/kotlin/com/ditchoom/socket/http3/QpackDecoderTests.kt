package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Stateful [QpackDecoder]: instruction application, dynamic/post-base decode, blocking, acks. */
class QpackDecoderTests {
    private val emitted = mutableListOf<QpackDecoderInstruction>()

    private fun decoder(maxCapacity: Long = 4096) = QpackDecoder(maxCapacity) { emitted += it }

    /** Build an encoded field section: prefix(RIC, base) then [writeLines] writes the representations. */
    private fun section(
        requiredInsertCount: Long,
        base: Long,
        maxEntries: Long = 4096 / 32,
        writeLines: PlatformBuffer.() -> Unit,
    ): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(256)
        QpackFieldSectionPrefix.encode(buf, requiredInsertCount, base, maxEntries)
        buf.writeLines()
        buf.resetForRead()
        return buf
    }

    /** Dynamic Indexed Field Line (§4.5.2, T=0): relative to Base. */
    private fun PlatformBuffer.dynamicIndexed(relativeIndex: Long) =
        QpackPrefixedInteger.encode(this, relativeIndex, prefixBits = 6, firstByteFlags = 0x80)

    /** Indexed Field Line with Post-Base Index (§4.5.3). */
    private fun PlatformBuffer.postBaseIndexed(postBaseIndex: Long) =
        QpackPrefixedInteger.encode(this, postBaseIndex, prefixBits = 4, firstByteFlags = 0x10)

    @Test
    fun appliesInsertsAndDecodesDynamicIndexedRelativeToBase() =
        runTest {
            val d = decoder()
            d.applyEncoderInstruction(QpackEncoderInstruction.SetCapacity(4096))
            d.applyEncoderInstruction(QpackEncoderInstruction.InsertWithLiteralName("x-a", "1")) // abs 0
            d.applyEncoderInstruction(QpackEncoderInstruction.InsertWithLiteralName("x-b", "2")) // abs 1
            assertEquals(2L, d.insertCountValue)

            // RIC=2, Base=2: relIndex 1 → abs 0 (x-a), relIndex 0 → abs 1 (x-b).
            val buf =
                section(requiredInsertCount = 2, base = 2) {
                    dynamicIndexed(1)
                    dynamicIndexed(0)
                }
            val fields = d.decodeSection(buf, streamId = 0, scratchPool = null)
            assertEquals(listOf(QpackHeaderField("x-a", "1"), QpackHeaderField("x-b", "2")), fields)
            // A dynamic section (RIC>0) acks; each insert emitted an Insert Count Increment.
            assertTrue(emitted.contains(QpackDecoderInstruction.SectionAck(0)))
            assertEquals(2, emitted.count { it is QpackDecoderInstruction.InsertCountIncrement })
        }

    @Test
    fun decodesPostBaseReferences() =
        runTest {
            val d = decoder()
            d.applyEncoderInstruction(QpackEncoderInstruction.SetCapacity(4096))
            d.applyEncoderInstruction(QpackEncoderInstruction.InsertWithLiteralName("x-a", "1")) // abs 0
            d.applyEncoderInstruction(QpackEncoderInstruction.InsertWithLiteralName("x-b", "2")) // abs 1

            // RIC=2, Base=0 → all references are post-Base: postIndex 0 → abs 0, postIndex 1 → abs 1.
            val buf =
                section(requiredInsertCount = 2, base = 0) {
                    postBaseIndexed(0)
                    postBaseIndexed(1)
                }
            assertEquals(
                listOf(QpackHeaderField("x-a", "1"), QpackHeaderField("x-b", "2")),
                d.decodeSection(buf, streamId = 4, scratchPool = null),
            )
        }

    @Test
    fun insertWithNameRefUsesStaticAndDynamicNames() =
        runTest {
            val d = decoder()
            d.applyEncoderInstruction(QpackEncoderInstruction.SetCapacity(4096))
            // Static index 0 is ":authority"; reuse its name with a literal value.
            d.applyEncoderInstruction(QpackEncoderInstruction.InsertWithNameRef(0, isStatic = true, value = "example.com")) // abs 0
            // Duplicate abs 0 (relative index 0).
            d.applyEncoderInstruction(QpackEncoderInstruction.Duplicate(0)) // abs 1, copy of abs 0

            val buf = section(requiredInsertCount = 2, base = 2) { dynamicIndexed(0) } // abs 1
            assertEquals(
                listOf(QpackHeaderField(":authority", "example.com")),
                d.decodeSection(buf, streamId = 0, scratchPool = null),
            )
        }

    @Test
    fun staticOnlySectionDecodesWithoutAck() =
        runTest {
            val d = decoder()
            // RIC=0, Base=0, a single static indexed line (index 1 = ":path" "/").
            val buf =
                section(requiredInsertCount = 0, base = 0) {
                    QpackPrefixedInteger.encode(this, 1, prefixBits = 6, firstByteFlags = 0xC0)
                }
            val fields = d.decodeSection(buf, streamId = 0, scratchPool = null)
            assertEquals(QpackStaticTable.entry(1), fields.single())
            assertTrue(emitted.none { it is QpackDecoderInstruction.SectionAck }, "RIC=0 ⇒ no Section Ack")
        }

    @Test
    fun blockedSectionWaitsForInserts() =
        runTest {
            val d = decoder()
            d.applyEncoderInstruction(QpackEncoderInstruction.SetCapacity(4096))

            val result = CompletableDeferred<List<QpackHeaderField>>()
            val buf = section(requiredInsertCount = 1, base = 1) { dynamicIndexed(0) } // needs abs 0
            val job = launch { result.complete(d.decodeSection(buf, streamId = 0, scratchPool = null)) }

            assertTrue(!result.isCompleted, "decode must block until the table reaches Required Insert Count")
            d.applyEncoderInstruction(QpackEncoderInstruction.InsertWithLiteralName("x-late", "v")) // abs 0 arrives
            job.join()
            assertEquals(listOf(QpackHeaderField("x-late", "v")), result.await())
        }

    @Test
    fun danglingDynamicReferenceFails() =
        runTest {
            val d = decoder()
            d.applyEncoderInstruction(QpackEncoderInstruction.SetCapacity(4096))
            d.applyEncoderInstruction(QpackEncoderInstruction.InsertWithLiteralName("x-a", "1")) // abs 0
            // Base=1 with relIndex 5 → abs = 1-1-5 = -5: no such entry.
            val buf = section(requiredInsertCount = 1, base = 1) { dynamicIndexed(5) }
            assertFailsWith<DecodeException> { d.decodeSection(buf, streamId = 0, scratchPool = null) }
        }

    @Test
    fun setCapacityAboveMaxIsEncoderStreamError() =
        runTest {
            val d = decoder(maxCapacity = 1024)
            val e =
                assertFailsWith<Http3StreamException> {
                    d.applyEncoderInstruction(QpackEncoderInstruction.SetCapacity(2048))
                }
            assertEquals(Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR, e.errorCode)
        }
}
