package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.transport.ByteStream
import com.ditchoom.socket.transport.MemoryTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlin.coroutines.CoroutineContext

/**
 * In-memory [QuicConnection] for testing. Supports:
 * - Opening client-initiated streams (returns bidirectional in-memory pairs)
 * - Injecting peer-initiated streams
 * - State transitions
 * - Error injection
 */
class MockQuicConnection(
    initialState: QuicConnectionState = QuicConnectionState.Established("h3"),
) : QuicConnection {
    private val mockScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val coroutineContext: CoroutineContext = mockScope.coroutineContext
    private val _state = MutableStateFlow<QuicConnectionState>(initialState)
    override val state: StateFlow<QuicConnectionState> = _state

    private var nextClientStreamId = 0L // client-initiated bidi: 0, 4, 8, ...
    private val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    private var closed = false

    /** The server-side ByteStream for each opened stream, keyed by stream ID. */
    val peerStreams = mutableMapOf<QuicStreamId, ByteStream>()

    override suspend fun openStream(): QuicByteStream {
        check(!closed) { "MockQuicConnection is closed" }
        check(_state.value is QuicConnectionState.Established) {
            "Cannot open stream in state ${_state.value}"
        }
        val streamId = QuicStreamId(nextClientStreamId)
        nextClientStreamId += 4 // client bidi streams: 0, 4, 8, ...
        val (client, server) = MemoryTransport.createPair(BufferFactory.Default)
        peerStreams[streamId] = server
        return QuicByteStream(streamId, client)
    }

    override suspend fun acceptStream(): QuicByteStream {
        check(!closed) { "MockQuicConnection is closed" }
        return incomingStreams.receive()
    }

    override fun streams(): Flow<QuicByteStream> = incomingStreams.consumeAsFlow()

    override suspend fun close(error: QuicError) {
        if (closed) return
        closed = true
        _state.value = QuicConnectionState.Closed(error)
        incomingStreams.close()
    }

    // --- Test helpers ---

    /** Inject a peer-initiated stream into [acceptStream] / [streams]. */
    fun injectPeerStream(streamId: QuicStreamId): Pair<QuicByteStream, ByteStream> {
        val (peerSide, localSide) = MemoryTransport.createPair(BufferFactory.Default)
        val stream = QuicByteStream(streamId, localSide)
        incomingStreams.trySend(stream)
        return stream to peerSide
    }

    /** Transition state (for testing state machine assertions). */
    fun transitionTo(newState: QuicConnectionState) {
        _state.value = newState
    }
}
