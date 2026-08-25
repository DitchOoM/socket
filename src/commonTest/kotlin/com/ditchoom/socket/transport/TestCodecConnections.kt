package com.ditchoom.socket.transport

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ByteStreamMux
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Test construction of a [CodecConnection] with the #382 writer parameters filled in.
 *
 * [CodecConnection] requires `scope`, `outboundCapacity` and `overflowPolicy` with no defaults on
 * purpose — a consumer must state them. Tests are not that consumer: most of them care about framing,
 * errors or reconnection and not about queue policy, so repeating the same three arguments 63 times
 * would bury what each test is actually about. Tests that *are* about the policy pass their own.
 *
 * ## The scope is a child with its own Job, deliberately
 *
 * The writer is a child of the scope it is given, which means a scope does not complete until the
 * connection is closed — that is what "writer lifetime = connection lifetime" buys, and in production
 * it is the correct behaviour. Inside `runTest` it is also a trap: `runTest` waits for the children of
 * its `TestScope`, so any test that builds a connection and does not close it would hang rather than
 * fail, which is the worst way for a test to break.
 *
 * So the writer gets `coroutineContext + Job()`: the **same dispatcher and scheduler** as the calling
 * test — virtual time and single-threaded determinism are preserved, which the concurrency tests
 * depend on — but an independent job, so forgetting to close costs a leaked parked coroutine instead
 * of a hung suite.
 */
internal fun <T> CoroutineScope.testCodecConnection(
    stream: ByteStream,
    codec: Codec<T>,
    config: TransportConfig = TransportConfig(),
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
    id: Long = 0L,
    outboundCapacity: Int = DEFAULT_TEST_OUTBOUND_CAPACITY,
    overflowPolicy: OverflowPolicy<T> = OverflowPolicy.Suspend,
): CodecConnection<T> =
    CodecConnection(
        stream = stream,
        codec = codec,
        scope = CoroutineScope(coroutineContext + Job()),
        outboundCapacity = outboundCapacity,
        overflowPolicy = overflowPolicy,
        config = config,
        decodeContext = decodeContext,
        encodeContext = encodeContext,
        id = id,
    )

/** [testCodecConnection]'s equivalent for the typed mux view, which mints connections the same way. */
internal fun <T> CoroutineScope.testTypedMuxView(
    raw: ByteStreamMux,
    codec: Codec<T>,
    config: TransportConfig = TransportConfig(),
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
    outboundCapacity: Int = DEFAULT_TEST_OUTBOUND_CAPACITY,
    overflowPolicy: OverflowPolicy<T> = OverflowPolicy.Suspend,
): TypedMuxView<T> =
    TypedMuxView(
        raw = raw,
        codec = codec,
        scope = CoroutineScope(coroutineContext + Job()),
        outboundCapacity = outboundCapacity,
        overflowPolicy = overflowPolicy,
        config = config,
        decodeContext = decodeContext,
        encodeContext = encodeContext,
    )

/**
 * Generous enough that no existing test hits it by accident, so a queue-full assertion anywhere is a
 * test that meant to make one. [OverflowPolicy.Suspend] is the default for the same reason: it is the
 * only policy that never silently discards a message, so a test that is not about overflow cannot lose
 * one without saying so.
 */
internal const val DEFAULT_TEST_OUTBOUND_CAPACITY = 64

/**
 * [testCodecConnection]'s equivalent for the unidirectional leaf, which gained the same writer in
 * #469 and therefore the same three required parameters.
 */
internal fun <T> CoroutineScope.testCodecSender(
    sink: ByteSink,
    codec: Codec<T>,
    config: TransportConfig = TransportConfig(),
    encodeContext: EncodeContext = EncodeContext.Empty,
    id: Long = 0L,
    outboundCapacity: Int = DEFAULT_TEST_OUTBOUND_CAPACITY,
    overflowPolicy: OverflowPolicy<T> = OverflowPolicy.Suspend,
): CodecSender<T> =
    CodecSender(
        sink = sink,
        codec = codec,
        scope = CoroutineScope(coroutineContext + Job()),
        outboundCapacity = outboundCapacity,
        overflowPolicy = overflowPolicy,
        config = config,
        encodeContext = encodeContext,
        id = id,
    )
