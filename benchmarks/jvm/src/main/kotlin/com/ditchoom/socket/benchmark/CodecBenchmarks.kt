package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.http3.QpackFieldSectionCodec
import com.ditchoom.socket.http3.QpackHeaderField
import com.ditchoom.socket.http3.VarIntCodec
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * QPACK field-section encode and decode (RFC 9204, static table + Huffman): every HTTP/3 request and
 * response pays both, once per HEADERS frame. The header set is a typical browser GET.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class QpackFieldSection {
    private val headers =
        listOf(
            QpackHeaderField(":method", "GET"),
            QpackHeaderField(":scheme", "https"),
            QpackHeaderField(":authority", "www.example.com"),
            QpackHeaderField(":path", "/assets/app/main.0f3a9c.js?v=20261108"),
            QpackHeaderField("user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"),
            QpackHeaderField("accept", "*/*"),
            QpackHeaderField("accept-encoding", "gzip, deflate, br"),
            QpackHeaderField("accept-language", "en-US,en;q=0.9"),
            QpackHeaderField("cookie", "session=6f1c2a9e4b7d4e0f8a3b5c7d9e1f2a3b; theme=dark"),
            QpackHeaderField("x-request-id", "8d3f0c3e-6a1b-4c55-9f2e-7b8a9c0d1e2f"),
        )

    private lateinit var encodeTarget: PlatformBuffer
    private lateinit var encoded: PlatformBuffer

    @Setup(Level.Trial)
    fun setUp() {
        encodeTarget = BufferFactory.deterministic().allocate(4096)
        encoded = BufferFactory.deterministic().allocate(4096)
        QpackFieldSectionCodec.encode(encoded, headers, EncodeContext.Empty)
        encoded.resetForRead()
    }

    @Benchmark
    fun encode(): Int {
        encodeTarget.resetForWrite()
        QpackFieldSectionCodec.encode(encodeTarget, headers, EncodeContext.Empty)
        return encodeTarget.position()
    }

    @Benchmark
    fun decode(): List<QpackHeaderField> {
        encoded.position(0)
        return QpackFieldSectionCodec.decode(encoded, DecodeContext.Empty)
    }
}

/**
 * QUIC variable-length integers (RFC 9000 §16): every HTTP/3 frame header and every QPACK length is
 * one. One op encodes then decodes a mix of all four widths.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class VarInt {
    private val values = longArrayOf(37, 15_293, 494_878_333, 151_288_809_941_952_652)
    private lateinit var buffer: PlatformBuffer

    @Setup(Level.Trial)
    fun setUp() {
        buffer = BufferFactory.deterministic().allocate(64)
    }

    @Benchmark
    @OperationsPerInvocation(4)
    fun encodeDecode(bh: Blackhole) {
        buffer.resetForWrite()
        for (v in values) VarIntCodec.encode(buffer, v, EncodeContext.Empty)
        buffer.resetForRead()
        repeat(values.size) { bh.consume(VarIntCodec.decode(buffer, DecodeContext.Empty)) }
    }
}
