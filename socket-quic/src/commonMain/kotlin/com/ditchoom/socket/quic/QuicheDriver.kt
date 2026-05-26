@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * Drives a single quiche connection from one coroutine. No mutexes, no polling.
 *
 * The command channel IS the event loop. When no commands arrive, the coroutine
 * is suspended — zero CPU, zero wakeups. Timeouts are integrated via [select]:
 * the driver queries quiche's timeout after each command and waits for either
 * the next command or the timeout to fire — no separate timeout coroutine.
 *
 * Platform-specific I/O is abstracted via [UdpChannel].
 * Platform-specific quiche bindings are abstracted via [QuicheApi].
 *
 * No `closed` boolean — channel closure IS the lifecycle state.
 */
class QuicheDriver(
    private val api: QuicheApi,
    private val conn: QuicheConn,
    private val bufferFactory: BufferFactory,
    private val recvInfo: QuicheRecvInfo,
    private val sendInfo: QuicheSendInfo,
    private val udpChannel: UdpChannel,
    private val clientMode: Boolean = true,
    private val isServer: Boolean = false,
    /**
     * Called from [cleanup] after all quiche handles have been freed. Used by callers
     * to release platform-owned memory referenced by [recvInfo] (peer/local sockaddrs)
     * whose raw pointers are cached inside the recv_info struct. The closure itself
     * keeps those Kotlin-side holders strongly reachable for the driver's lifetime —
     * without it, JVM `DirectByteBuffer`-backed sockaddr buffers can be reclaimed by
     * GC mid-connection, leaving recvInfo.from dangling. See: socket-quic JVM panic at
     * quiche/src/ffi.rs:2059 ("unsupported address type").
     */
    private val onCleanup: () -> Unit = {},
) {
    val commands = Channel<QuicheCmd>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)
    val state: StateFlow<QuicConnectionState> = _state

    val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    private val streams = mutableMapOf<Long, StreamSlot>()
    private var nextStreamId = if (isServer) 1L else 0L

    private val udpSendBuf: PlatformBuffer = bufferFactory.allocate(MAX_DATAGRAM_SIZE)
    private val sendAddr = udpSendBuf.nativeMemoryAccess!!.nativeAddress.toLong()
    private var driverJob: Job? = null

    /**
     * Client-mode recv buffer pool — mirrors the server-side pool in
     * CommonJvmQuicServerEngine. Only allocated in [clientMode] because
     * server-accepted drivers receive packets via commands.send() from the
     * server's receive loop and never run [udpReaderLoop].
     *
     * MultiThreaded mode: [udpReaderLoop] acquires on its own Dispatchers.Default
     * coroutine; the driver's [run] loop releases (via [QuicheCmd.RecvPacket]'s
     * `freeNativeMemory()` in [execute] or [failCommand]) on a different
     * Dispatchers.Default coroutine — different threads under load.
     * maxPoolSize=64 → ~87 KB cached (64 × 1350), generous for a single
     * connection's in-flight datagram count.
     */
    private val recvBufPool: BufferPool? =
        if (clientMode) {
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                maxPoolSize = 64,
                defaultBufferSize = MAX_DATAGRAM_SIZE,
                factory = bufferFactory,
            )
        } else {
            null
        }

    fun start(scope: CoroutineScope) {
        driverJob = scope.launch(Dispatchers.Default) { run() }

        if (clientMode) {
            scope.launch(Dispatchers.Default) { udpReaderLoop() }
        }
    }

    /**
     * The reactive driver loop. Suspends on command channel or quiche timeout — zero CPU when idle.
     * Timeout is integrated via [select]: no separate timeout coroutine, no polling.
     */
    private suspend fun run() {
        try {
            afterCommand() // initial flush (e.g., ClientHello or ServerHello response)
            while (true) {
                val timeout = api.connTimeout(conn)
                val cmd =
                    if (timeout == null) {
                        // No timeout set — block until next command (or channel close)
                        commands.receiveCatching().getOrNull() ?: break
                    } else {
                        select<QuicheCmd?> {
                            commands.onReceiveCatching { it.getOrNull() }
                            onTimeout(timeout) { null }
                        }
                    }
                // null from onReceiveCatching means channel closed — exit
                if (cmd == null && commands.isClosedForReceive) break
                if (cmd != null) execute(cmd) else api.connOnTimeout(conn)
                afterCommand()
            }
        } finally {
            cleanup()
        }
    }

    private fun execute(cmd: QuicheCmd) {
        when (cmd) {
            is QuicheCmd.RecvPacket -> {
                val addr =
                    cmd.buf.nativeMemoryAccess!!
                        .nativeAddress
                        .toLong()
                api.connRecv(conn, addr, cmd.len, recvInfo)
                cmd.buf.freeNativeMemory()
            }

            is QuicheCmd.OpenStream -> {
                val id = QuicStreamId(nextStreamId)
                nextStreamId += 4
                val slot = StreamSlot(id)
                streams[id.id] = slot
                cmd.result.complete(slot)
            }

            is QuicheCmd.StreamRecv -> {
                val result = api.connStreamRecv(conn, QuicStreamId(cmd.streamId), cmd.addr, cmd.bufLen)
                cmd.result.complete(result)
            }

            is QuicheCmd.StreamSend -> {
                val written = api.connStreamSend(conn, QuicStreamId(cmd.streamId), cmd.addr, cmd.bufLen, cmd.fin)
                cmd.result.complete(written)
            }

            is QuicheCmd.Close -> {
                api.connClose(conn, cmd.error)
                cmd.result.complete(Unit)
            }
        }
    }

    private suspend fun afterCommand() {
        flushOutgoing()
        discoverNewStreams()
        updateState()
    }

    private fun discoverNewStreams() {
        val iter = api.connReadable(conn)
        if (iter.isExhausted) return
        try {
            while (true) {
                val streamId = api.streamIterNext(iter) ?: break
                val existing = streams[streamId.id]
                if (existing != null) {
                    existing.dataSignal.trySend(Unit)
                } else {
                    val slot = StreamSlot(streamId)
                    streams[streamId.id] = slot
                    val adapter = DriverStreamAdapter(this, slot)
                    val byteStream = QuicheStreamByteStream(streamId, adapter, bufferFactory)
                    incomingStreams.trySend(QuicByteStream(streamId, byteStream))
                    slot.dataSignal.trySend(Unit)
                }
            }
        } finally {
            api.streamIterFree(iter)
        }
    }

    private fun updateState() {
        if (api.connIsEstablished(conn) && _state.value is QuicConnectionState.Handshaking) {
            _state.value = QuicConnectionState.Established("h3")
        }
        if (api.connIsClosed(conn) && _state.value !is QuicConnectionState.Closed) {
            _state.value = QuicConnectionState.Closed(null)
            commands.close()
        }
    }

    private suspend fun flushOutgoing() {
        while (true) {
            val written = api.connSend(conn, sendAddr, MAX_DATAGRAM_SIZE, sendInfo)
            if (written <= 0) break
            try {
                udpChannel.send(udpSendBuf, written)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                // UDP send failed (peer unreachable, channel closed during shutdown, etc).
                // The connection cannot make further progress — short-circuit to Closed and
                // let the driver loop unwind via cleanup(). Letting the exception escape
                // would leak it as an uncaught coroutine failure into the parent scope.
                if (_state.value !is QuicConnectionState.Closed) {
                    _state.value = QuicConnectionState.Closed(null)
                    commands.close()
                }
                return
            }
        }
    }

    /**
     * Client-mode: async UDP reader using [UdpChannel].
     * Suspends until data arrives — zero CPU when no packets.
     */
    private suspend fun udpReaderLoop() {
        val pool = recvBufPool!!
        try {
            while (coroutineContext[Job]?.isActive != false) {
                val buf = pool.allocate(MAX_DATAGRAM_SIZE)
                val received =
                    try {
                        udpChannel.receive(buf)
                    } catch (_: Exception) {
                        buf.freeNativeMemory()
                        if (commands.isClosedForSend) return
                        continue
                    }
                if (received > 0) {
                    commands.send(QuicheCmd.RecvPacket(buf, received))
                } else {
                    buf.freeNativeMemory()
                }
            }
        } catch (_: ClosedSendChannelException) {
            // Driver closed
        }
    }

    private fun cleanup() {
        commands.close()

        while (true) {
            val cmd = commands.tryReceive().getOrNull() ?: break
            failCommand(cmd)
        }

        for (slot in streams.values) {
            slot.dataSignal.close()
        }
        streams.clear()
        api.connFree(conn)
        api.recvInfoFree(recvInfo)
        api.sendInfoFree(sendInfo)
        udpSendBuf.freeNativeMemory()
        // commands.tryReceive() drain above freed any pending RecvPackets
        // back to the pool. Late releases from an in-flight udpReaderLoop
        // iteration are benign — they repopulate the pool, which is GC'd
        // with the driver.
        recvBufPool?.clear()
        incomingStreams.close()
        // Released last — quiche may have dereferenced recvInfo.from/to inside
        // any of the api.*Free() calls above. Safe to release the underlying
        // sockaddr storage only after the conn/recvInfo handles are gone.
        onCleanup()
    }

    private fun failCommand(cmd: QuicheCmd) {
        when (cmd) {
            is QuicheCmd.RecvPacket -> cmd.buf.freeNativeMemory()
            is QuicheCmd.OpenStream ->
                cmd.result.completeExceptionally(
                    SocketClosedException.General("connection closed"),
                )
            is QuicheCmd.StreamRecv -> cmd.result.complete(StreamRecvResult.Error(-2))
            is QuicheCmd.StreamSend -> cmd.result.complete(-1)
            is QuicheCmd.Close -> cmd.result.complete(Unit)
        }
    }

    suspend fun destroy() {
        commands.close()
        driverJob?.join()
    }

    companion object {
        const val MAX_DATAGRAM_SIZE = 1350
    }
}

/**
 * [QuicheStreamAdapter] that submits commands to the [QuicheDriver].
 * Uses [StreamSlot.dataSignal] for reactive reads — no polling.
 */
class DriverStreamAdapter(
    private val driver: QuicheDriver,
    private val slot: StreamSlot,
) : QuicheStreamAdapter {
    override suspend fun streamRead(
        streamId: QuicStreamId,
        bufferFactory: BufferFactory,
        bufferSize: Int,
        timeout: Duration,
    ): ReadResult {
        val buffer = bufferFactory.allocate(bufferSize)
        val addr = buffer.nativeMemoryAccess!!.nativeAddress.toLong()

        try {
            return withTimeout(timeout) {
                while (true) {
                    val deferred = CompletableDeferred<StreamRecvResult>()
                    driver.commands.send(QuicheCmd.StreamRecv(streamId.id, addr, bufferSize, deferred))
                    when (val result = deferred.await()) {
                        is StreamRecvResult.Data -> {
                            if (result.bytesRead == 0 && result.fin) {
                                buffer.freeNativeMemory()
                                return@withTimeout ReadResult.End
                            }
                            if (result.bytesRead > 0) {
                                buffer.position(result.bytesRead)
                                buffer.resetForRead()
                                return@withTimeout ReadResult.Data(buffer)
                            }
                        }
                        is StreamRecvResult.Done -> {
                            slot.dataSignal.receive()
                            continue
                        }
                        is StreamRecvResult.Error -> {
                            buffer.freeNativeMemory()
                            return@withTimeout ReadResult.End
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                ReadResult.End
            }
        } catch (_: ClosedSendChannelException) {
            buffer.freeNativeMemory()
            return ReadResult.End
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            buffer.freeNativeMemory()
            return ReadResult.End
        }
    }

    override suspend fun streamWrite(
        streamId: QuicStreamId,
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val addr = buffer.nativeMemoryAccess!!.nativeAddress.toLong() + buffer.position()
        val remaining = buffer.remaining()

        return try {
            withTimeout(timeout) {
                val deferred = CompletableDeferred<Int>()
                driver.commands.send(QuicheCmd.StreamSend(streamId.id, addr, remaining, false, deferred))
                deferred.await()
            }.also { written ->
                if (written < 0) {
                    throw SocketClosedException.General("quiche stream write error: $written")
                }
            }
        } catch (_: ClosedSendChannelException) {
            throw SocketClosedException.General("connection closed")
        }
    }

    override suspend fun streamClose(streamId: QuicStreamId) {
        try {
            val deferred = CompletableDeferred<Int>()
            driver.commands.send(QuicheCmd.StreamSend(streamId.id, 0L, 0, true, deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            // Connection already closed
        }
    }
}
