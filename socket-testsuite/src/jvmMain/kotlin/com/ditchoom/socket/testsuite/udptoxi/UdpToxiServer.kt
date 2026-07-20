@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.testsuite.udptoxi

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.allocate
import com.ditchoom.socket.testkit.fault.ByteEdit
import com.ditchoom.socket.testkit.fault.FaultSchedule
import com.ditchoom.socket.testkit.fault.FaultScheduleCodec
import com.ditchoom.socket.testkit.fault.ImpairmentEngine
import com.ditchoom.socket.testkit.fault.UnitDecision
import com.ditchoom.socket.testsuite.harness.HarnessJson
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The `udp-toxi` relay sidecar (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §5, §11.1) — the UDP/QUIC analogue
 * of toxiproxy, which is TCP-only. It runs the harness's **two-plane** design:
 *
 *  - **Control plane (TCP):** a hand-rolled HTTP/1.1 REST API over the library's own [ServerSocket]
 *    (same technique as the harness controller), driven by [com.ditchoom.socket.testsuite.harness.UdpToxiClient].
 *    It provisions named relays and installs a per-direction [FaultSchedule], deserialized with the
 *    shared [FaultScheduleCodec] — so the relay drives the exact [ImpairmentEngine] the in-process
 *    Tier-A `ImpairedDatagramPipe` does. That shared interpretation is the Tier-A ⇄ Tier-C parity
 *    guarantee.
 *  - **Data plane (UDP):** each relay binds a [UdpSocket] on its `listen` port, forwards datagrams to a
 *    fixed `upstream` (e.g. `udp-echo`), applies the client→server schedule on the way out and the
 *    server→client schedule on the replies. A datagram is a transport *unit*; the engine decides its
 *    fate (drop / delay / duplicate / corrupt / reorder) in send order.
 *
 * **REST surface**
 * ```
 * GET    /health                              → 200 "ok"
 * POST   /relays            {name,listen,upstream}   → 200 ; bind + reset schedules (idempotent)
 * POST   /relays/{name}/schedule?direction=…  body = FaultScheduleCodec  → 200 ; install one leg
 * DELETE /relays/{name}/schedule              → 200 ; clear both legs to CLEAN
 * ```
 *
 * The relay dogfoods `:socket-udp` and touches **no `ByteArray`** — payloads are copied and (for
 * corruption) edited entirely through [ReadBuffer]/[PlatformBuffer], honouring the production
 * no-ByteArray discipline even though this is test-harness infrastructure.
 */
object UdpToxiServer {
    /** Multi-peer relay registry. Provisioning is serialized so a re-`POST /relays` never races a bind. */
    private val relays = mutableMapOf<String, Relay>()
    private val relaysLock = Mutex()

    /**
     * Bind the control-plane HTTP server on [controlBindHost]:[controlPort] and serve until cancelled.
     * Relay data-plane coroutines are launched into [this] scope's supervisor, so they outlive the
     * individual control connections that provision them.
     */
    suspend fun serve(
        controlBindHost: String,
        controlPort: Int,
    ) {
        supervisorScope {
            val relayScope = this
            val server = ServerSocket.allocate()
            val clients = server.bind(port = controlPort, host = controlBindHost)
            println("[UdpToxiServer] control plane listening on $controlBindHost:${server.port()}")
            clients.collect { client ->
                launch {
                    try {
                        handle(client, relayScope)
                    } catch (_: Throwable) {
                        // Per-connection failure (hangup, malformed request, timeout) is not fatal.
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    // ── control plane ───────────────────────────────────────────────────────

    private suspend fun handle(
        socket: ClientSocket,
        relayScope: CoroutineScope,
    ) {
        val request = readRequest(socket) ?: return
        val response =
            when {
                request.method == "GET" && request.path == "/health" -> ok("ok")
                request.method == "POST" && request.path == "/relays" -> handleUpsert(request.body, relayScope)
                request.method == "POST" && request.path.matches(SCHEDULE_PATH) ->
                    handleSetSchedule(request.path, request.query, request.body)
                request.method == "DELETE" && request.path.matches(SCHEDULE_PATH) ->
                    handleClearSchedule(request.path)
                else -> status("404 Not Found", "not found")
            }
        socket.writeString(response, Charset.UTF8, 5.seconds)
    }

    private suspend fun handleUpsert(
        body: String,
        relayScope: CoroutineScope,
    ): String {
        val name = HarnessJson.stringField(body, "name") ?: return status("400 Bad Request", "missing name")
        val listen = HarnessJson.intField(body, "listen") ?: return status("400 Bad Request", "missing listen")
        val upstream = HarnessJson.stringField(body, "upstream") ?: return status("400 Bad Request", "missing upstream")
        relaysLock.withLock {
            val existing = relays[name]
            if (existing != null) {
                existing.clearSchedules()
            } else {
                relays[name] = Relay.open(name, listen, upstream, relayScope)
            }
        }
        return ok("ok")
    }

    private fun handleSetSchedule(
        path: String,
        query: String,
        body: String,
    ): String {
        val name = SCHEDULE_PATH.find(path)!!.groupValues[1]
        val relay = relays[name] ?: return status("404 Not Found", "no such relay: $name")
        val direction =
            when (queryParam(query, "direction")) {
                "clientToServer" -> Leg.CLIENT_TO_SERVER
                "serverToClient" -> Leg.SERVER_TO_CLIENT
                else -> return status("400 Bad Request", "direction must be clientToServer|serverToClient")
            }
        val schedule =
            try {
                FaultScheduleCodec.decode(body)
            } catch (e: IllegalArgumentException) {
                return status("400 Bad Request", "bad schedule: ${e.message}")
            }
        relay.setSchedule(direction, schedule)
        return ok("ok")
    }

    private fun handleClearSchedule(path: String): String {
        val name = SCHEDULE_PATH.find(path)!!.groupValues[1]
        val relay = relays[name] ?: return status("404 Not Found", "no such relay: $name")
        relay.clearSchedules()
        return ok("ok")
    }

    private val SCHEDULE_PATH = Regex("^/relays/([^/?]+)/schedule$")

    private fun queryParam(
        query: String,
        key: String,
    ): String? =
        query.split('&').firstNotNullOfOrNull { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq) == key) pair.substring(eq + 1) else null
        }

    // ── HTTP request/response plumbing ──────────────────────────────────────

    private class Request(
        val method: String,
        val path: String,
        val query: String,
        val body: String,
    )

    /** Read the request head, then exactly `Content-Length` body bytes (ASCII). Null on client hangup. */
    private suspend fun readRequest(socket: ClientSocket): Request? {
        val buf = StringBuilder()
        var headerEnd = -1
        while (headerEnd < 0) {
            if (buf.length > MAX_REQUEST_CHARS) return null
            headerEnd = buf.indexOf("\r\n\r\n")
            if (headerEnd >= 0) break
            val chunk =
                try {
                    socket.readBuffer(5.seconds)
                } catch (_: Throwable) {
                    return null
                }
            repeat(chunk.remaining()) { buf.append(chunk.readByte().toInt().toChar()) }
        }
        val head = buf.substring(0, headerEnd)
        val requestLine = head.substringBefore("\r\n")
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return null
        val rawPath = parts.getOrNull(1) ?: return null
        val path = rawPath.substringBefore('?')
        val query = rawPath.substringAfter('?', "")
        val contentLength = contentLengthOf(head)
        // Everything already buffered past the header boundary is the start of the body.
        var body = buf.substring(headerEnd + 4)
        while (body.length < contentLength) {
            val chunk =
                try {
                    socket.readBuffer(5.seconds)
                } catch (_: Throwable) {
                    return null
                }
            val sb = StringBuilder(body)
            repeat(chunk.remaining()) { sb.append(chunk.readByte().toInt().toChar()) }
            body = sb.toString()
        }
        return Request(method, path, query, body.substring(0, contentLength.coerceAtMost(body.length)))
    }

    private fun contentLengthOf(head: String): Int {
        for (line in head.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                return line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun ok(body: String): String = status("200 OK", body)

    private fun status(
        status: String,
        body: String,
    ): String =
        buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${body.length}\r\n") // ASCII body — char count == byte count
            append("Connection: close\r\n")
            append("\r\n")
            append(body)
        }

    private const val MAX_REQUEST_CHARS = 1 shl 16
}

/** Which leg of a relay a schedule impairs — mirrors [com.ditchoom.socket.testsuite.harness.RelayDirection]. */
private enum class Leg { CLIENT_TO_SERVER, SERVER_TO_CLIENT }

/** One buffered datagram waiting to be flushed on a channel's single send-coroutine, to [peer]. */
@OptIn(ExperimentalDatagramApi::class)
private class Outgoing(
    val payload: ReadBuffer,
    val peer: SocketAddress,
)

/**
 * A single named relay: a client-facing [UdpSocket] bound on `listen`, an upstream-facing socket
 * forwarding to `upstream`, and two independent [ImpairmentEngine]s (one per direction). Each physical
 * channel has exactly one receive-loop coroutine and one send-drain coroutine (a mailbox), honouring
 * the buffer-flow "confine receive and send each to one coroutine" contract — delayed/duplicated copies
 * post to the mailbox after their virtual hold elapses, and the drain flushes in receive order.
 */
@OptIn(ExperimentalDatagramApi::class)
private class Relay private constructor(
    private val name: String,
    private val clientFacing: DatagramChannel,
    private val upstreamSocket: DatagramChannel,
    private val upstreamAddress: SocketAddress,
    private val scope: CoroutineScope,
) {
    // decide() is stateful (seeded RNG + monotonic unit index), so a schedule swap installs a *fresh*
    // engine (index 0). Tests set a schedule before sending and clear it after, so no swap ever splits a
    // unit sequence. @Volatile: written by the control coroutine, read by the receive pumps.
    @Volatile
    private var clientToServer = ImpairmentEngine(FaultSchedule.CLEAN)

    @Volatile
    private var serverToClient = ImpairmentEngine(FaultSchedule.CLEAN)

    // The most recent client source — the reply target. @Volatile: written by the c2s pump, read by s2c.
    @Volatile
    private var lastClient: SocketAddress? = null

    private val toUpstream = Channel<Outgoing>(Channel.UNLIMITED)
    private val toClient = Channel<Outgoing>(Channel.UNLIMITED)

    fun setSchedule(
        leg: Leg,
        schedule: FaultSchedule,
    ) {
        when (leg) {
            Leg.CLIENT_TO_SERVER -> clientToServer = ImpairmentEngine(schedule)
            Leg.SERVER_TO_CLIENT -> serverToClient = ImpairmentEngine(schedule)
        }
    }

    fun clearSchedules() {
        clientToServer = ImpairmentEngine(FaultSchedule.CLEAN)
        serverToClient = ImpairmentEngine(FaultSchedule.CLEAN)
    }

    private fun start() {
        // client → upstream
        scope.launch {
            while (true) {
                val datagram = receiveOrNull(clientFacing) ?: break
                lastClient = datagram.peer
                impair(datagram.payload, clientToServer, toUpstream, upstreamAddress)
            }
        }
        // upstream → client
        scope.launch {
            while (true) {
                val datagram = receiveOrNull(upstreamSocket) ?: break
                val replyTo = lastClient ?: continue // nothing has come from a client yet — drop the reply
                impair(datagram.payload, serverToClient, toClient, replyTo)
            }
        }
        // send drains — one coroutine per physical channel
        scope.launch { drain(toUpstream, upstreamSocket) }
        scope.launch { drain(toClient, clientFacing) }
    }

    private suspend fun receiveOrNull(channel: DatagramChannel): Datagram? =
        when (val r = channel.receive()) {
            is DatagramReadResult.Received -> r.datagram
            is DatagramReadResult.Closed -> null
        }

    /**
     * Fold the direction's schedule over one received datagram, in send order, then enqueue each
     * delivered copy on [mailbox] after its hold. Drops enqueue nothing. [decide] is called exactly once
     * here, synchronously and in order — the delayed launches only flush already-decided copies.
     */
    private fun impair(
        payload: ReadBuffer,
        engine: ImpairmentEngine,
        mailbox: Channel<Outgoing>,
        target: SocketAddress,
    ) {
        when (val decision = engine.decide()) {
            is UnitDecision.Dropped -> {}
            is UnitDecision.Delivered ->
                decision.copies.forEach { copy ->
                    val out = Outgoing(snapshot(payload, copy.edits), target)
                    if (copy.afterDelay <= Duration.ZERO) {
                        mailbox.trySend(out)
                    } else {
                        scope.launch {
                            delay(copy.afterDelay)
                            mailbox.trySend(out)
                        }
                    }
                }
        }
    }

    private suspend fun drain(
        mailbox: Channel<Outgoing>,
        channel: DatagramChannel,
    ) {
        for (out in mailbox) {
            runCatching { channel.send(out.payload, to = out.peer) }
        }
    }

    companion object {
        suspend fun open(
            name: String,
            listenPort: Int,
            upstream: String,
            scope: CoroutineScope,
        ): Relay {
            val host = upstream.substringBeforeLast(':')
            val port = upstream.substringAfterLast(':').toInt()
            val clientFacing = UdpSocket.bind(localHost = "0.0.0.0", localPort = listenPort)
            val upstreamSocket = UdpSocket.bind(localHost = "0.0.0.0", localPort = 0)
            val upstreamAddress = UdpSocket.resolve(host, port)
            println("[UdpToxiServer] relay '$name' :$listenPort → $host:$port")
            return Relay(name, clientFacing, upstreamSocket, upstreamAddress, scope).also { it.start() }
        }

        /**
         * Copy [source]'s remaining bytes into a fresh owned buffer, XOR-flipping [edits] as it goes — a
         * buffer-only equivalent of the Tier-A pipe's edit step, with no `ByteArray`. `slice()` is a
         * non-consuming view, so the caller's buffer is untouched and can be snapshotted again for a
         * duplicate copy.
         */
        private fun snapshot(
            source: ReadBuffer,
            edits: List<ByteEdit>,
        ): PlatformBuffer {
            val view = source.slice()
            val n = view.remaining()
            val out = BufferFactory.Default.allocate(n)
            var i = 0
            while (i < n) {
                var b = view.readByte().toInt() and 0xFF
                for (edit in edits) if (edit.offset == i) b = b xor edit.flipMask
                out.writeByte(b.toByte())
                i++
            }
            out.position(0)
            out.setLimit(n)
            return out
        }
    }
}

fun main() {
    val bind = System.getenv("UDP_TOXI_BIND")?.trim().takeUnless { it.isNullOrEmpty() } ?: "0.0.0.0"
    val port = System.getenv("UDP_TOXI_API_PORT")?.trim()?.toIntOrNull() ?: 8475
    runBlocking {
        UdpToxiServer.serve(bind, port)
    }
}
