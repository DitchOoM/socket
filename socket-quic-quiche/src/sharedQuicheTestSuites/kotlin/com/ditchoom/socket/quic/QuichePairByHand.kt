package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.sim.resolve
import com.ditchoom.socket.udp.UdpSocket
import kotlin.random.Random
import kotlin.time.Duration

/**
 * A real client/server quiche pair with no driver and no pipe: every datagram either side writes is
 * returned to the test, which decides whether the other side receives it. For a scenario whose outcome
 * depends on the order of sends, losses and stream writes inside one quiche connection — an order the
 * sim's drivers settle by thread timing — this states the order instead.
 *
 * The client has [CLIENT_ENDPOINTS] local endpoints, numbered from 0 (the one it connects from), so a
 * scenario can probe and migrate between paths; each [Datagram] names the endpoint it crosses.
 *
 * Both connections read one virtual clock that moves only in [elapse], which also fires every quiche
 * timer falling due on the way; [bringTimerForward] sets one connection's clock ahead of it. Loss is
 * staged by not delivering a datagram; quiche declares it lost by packet threshold (RFC 9002 §6.1.1)
 * once three later packets are acknowledged, or by its timers.
 */
internal class QuichePairByHand private constructor(
    env: MigrationSimEnv,
    clientEndpoints: List<SocketAddress>,
    serverSocket: SocketAddress,
    options: QuicOptions,
    serverOptions: QuicOptions,
    configureClient: (QuicheConfig) -> Unit,
) {
    /** A connection's next quiche timer. */
    sealed interface Timer {
        data object Unarmed : Timer

        data class DueIn(
            val span: Duration,
        ) : Timer
    }

    /** How a connection stands. */
    enum class Ending { Open, IdleTimeout, ClosedLocally, ClosedByPeer }

    /** A datagram as the network carries it: its bytes, and the client endpoint it leaves or reaches. */
    class Datagram(
        val bytes: ByteArray,
        val clientEndpoint: Int,
    ) {
        /**
         * The packet types coalesced in this datagram, in order, read from the unprotected long headers
         * (RFC 9000 §17.2): `Initial`, `0-RTT`, `Handshake`, `Retry`, then `1-RTT` for a short header,
         * which always ends a datagram. Zero bytes after the last packet are padding, not a packet.
         */
        val packetTypes: List<String>
            get() = packets.map { it.first }

        /** The bytes of the first coalesced packet of [type] (one of [packetTypes]), header included. */
        fun packet(type: String): ByteArray = packets.first { it.first == type }.second.let { bytes.copyOfRange(it.first, it.last + 1) }

        /** Each coalesced packet's type and the bytes it spans, as [packetTypes] reads them. */
        private val packets: List<Pair<String, IntRange>>
            get() =
                buildList {
                    var at = 0
                    while (at < bytes.size) {
                        val start = at
                        val first = bytes[at].toInt() and 0xFF
                        if (first and 0x80 == 0) {
                            // A short header has the fixed bit set; zeros are padding after the last packet.
                            if (first and 0x40 != 0) add("1-RTT" to (start until bytes.size))
                            return@buildList
                        }
                        val type = (first shr 4) and 0x03
                        if (type == RETRY) {
                            add(LONG_HEADER_TYPES[type] to (start until bytes.size))
                            return@buildList
                        }
                        at += 5 // flags, version
                        at += 1 + (bytes[at].toInt() and 0xFF) // DCID
                        at += 1 + (bytes[at].toInt() and 0xFF) // SCID
                        if (type == INITIAL) {
                            val (tokenLength, width) = varint(at)
                            at += width + tokenLength.toInt()
                        }
                        val (length, width) = varint(at)
                        at += width + length.toInt()
                        add(LONG_HEADER_TYPES[type] to (start until at))
                    }
                }

        private fun varint(at: Int): Pair<Long, Int> {
            val first = bytes[at].toInt() and 0xFF
            val width = 1 shl (first shr 6)
            var value = (first and 0x3F).toLong()
            for (i in 1 until width) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            return value to width
        }

        private companion object {
            val LONG_HEADER_TYPES = listOf("Initial", "0-RTT", "Handshake", "Retry")
            const val INITIAL = 0
            const val RETRY = 3
        }
    }

    private val api = env.api
    private val bufferFactory = BufferFactory.network()
    private val clientAddrs = clientEndpoints.map { env.codec.encodeToNative(it, bufferFactory) }
    private val serverAddr = env.codec.encodeToNative(serverSocket, bufferFactory)
    private val clientCfg = api.configNew(QUICHE_V1)
    private val serverCfg = api.configNew(QUICHE_V1)
    private val datagram = bufferFactory.allocate(MAX_DATAGRAM)
    private val clientRecvInfos =
        clientAddrs.map { api.recvInfoNew(serverAddr.address, serverAddr.length, it.address, it.length) }
    private val serverRecvInfos =
        clientAddrs.map { api.recvInfoNew(it.address, it.length, serverAddr.address, serverAddr.length) }
    private val sendInfo = api.sendInfoNew()
    private val seqOut = bufferFactory.allocate(8)
    private val eventLocal = bufferFactory.allocate(SOCKADDR_STORAGE_SIZE)
    private val eventLocalLen = bufferFactory.allocate(4)
    private val eventPeer = bufferFactory.allocate(SOCKADDR_STORAGE_SIZE)
    private val eventPeerLen = bufferFactory.allocate(4)
    private val cidRandom = Random(CID_SEED)
    private var now: Duration = Duration.ZERO
    val client: QuicheConn
    val server: QuicheConn

    init {
        listOf(serverCfg to QuicRole.Server, clientCfg to QuicRole.Client).forEach { (cfg, role) ->
            val sideOptions = if (role == QuicRole.Server) serverOptions else options
            val alpn = encodeAlpnList(sideOptions.alpnProtocols, bufferFactory)
            api.configSetApplicationProtos(cfg, alpn.nativeMemoryAccess!!.nativeAddress.toLong(), alpn.remaining())
            alpn.freeNativeMemory()
            applyQuicOptions(sideOptions, SimQuicConfigCalls(api, cfg), role)
            if (role == QuicRole.Client) configureClient(cfg)
        }
        simNullTerminated(env.certChainPath, bufferFactory).let { buf ->
            check(api.configLoadCertChainFromPemFile(serverCfg, buf.nativeMemoryAccess!!.nativeAddress.toLong()) == 0)
            buf.freeNativeMemory()
        }
        simNullTerminated(env.privKeyPath, bufferFactory).let { buf ->
            check(api.configLoadPrivKeyFromPemFile(serverCfg, buf.nativeMemoryAccess!!.nativeAddress.toLong()) == 0)
            buf.freeNativeMemory()
        }
        val serverName = simNullTerminated("localhost", bufferFactory)
        val clientScid = generateScid(bufferFactory, Random(CLIENT_SEED))
        val primary = clientAddrs.first()
        client =
            at {
                api.connect(
                    serverName.nativeMemoryAccess!!.nativeAddress.toLong(),
                    "localhost".length,
                    clientScid.nativeMemoryAccess!!.nativeAddress.toLong(),
                    QUIC_ISSUED_CONN_ID_LEN,
                    primary.address,
                    primary.length,
                    serverAddr.address,
                    serverAddr.length,
                    clientCfg,
                )
            }
        serverName.freeNativeMemory()
        clientScid.freeNativeMemory()
        val serverScid = generateScid(bufferFactory, Random(SERVER_SEED))
        server =
            at {
                api.accept(
                    serverScid.nativeMemoryAccess!!.nativeAddress.toLong(),
                    QUIC_ISSUED_CONN_ID_LEN,
                    0L,
                    0,
                    serverAddr.address,
                    serverAddr.length,
                    primary.address,
                    primary.length,
                    serverCfg,
                )
            }
        serverScid.freeNativeMemory()
    }

    /** How far each connection's clock runs ahead of [now]: see [bringTimerForward]. */
    private val leads = mutableMapOf<QuicheConn, Duration>()

    private fun lead(conn: QuicheConn): Duration = leads.getOrElse(conn) { Duration.ZERO }

    /** How long the pair's clock has run. */
    val elapsed: Duration
        get() = now

    /** Run [block] with quiche's clock on this thread pinned to [now] plus [lead]. */
    private inline fun <T> at(
        lead: Duration = Duration.ZERO,
        block: () -> T,
    ): T {
        api.setThreadVirtualTimeNanos((now + lead).inWholeNanoseconds)
        try {
            return block()
        } finally {
            api.clearThreadVirtualTime()
        }
    }

    /**
     * Move the clock on by [span], firing each quiche timer of the [firing] connections as it falls due; [fired] runs
     * after each, at the time it fell due, as a driver's timer handler would.
     */
    fun elapse(
        span: Duration,
        firing: List<QuicheConn> = listOf(client, server),
        fired: (QuicheConn) -> Unit = {},
    ) {
        val until = now + span
        while (true) {
            val (due, conn) =
                firing
                    .mapNotNull { conn -> at(lead(conn)) { api.connTimeout(conn) }?.let { now + it to conn } }
                    .minByOrNull { it.first }
                    ?.takeIf { it.first <= until }
                    ?: break
            now = due
            at(lead(conn)) { api.connOnTimeout(conn) }
            fired(conn)
        }
        now = until
    }

    /**
     * Run [conn]'s quiche timeout once, now, as a driver does when its deadline falls due. A timer quiche re-arms
     * already overdue waits for the caller's next pass, after it has sent what this one wrote.
     */
    fun runTimeout(conn: QuicheConn) {
        at(lead(conn)) { api.connOnTimeout(conn) }
    }

    /** Run [block] on [conn]'s clock: quiche calls a driver makes outside this class read the pair's time. */
    fun <T> onClock(
        conn: QuicheConn,
        block: () -> T,
    ): T = at(lead(conn), block)

    /** [conn]'s next timer. */
    fun timer(conn: QuicheConn): Timer =
        when (val span = at(lead(conn)) { api.connTimeout(conn) }) {
            null -> Timer.Unarmed
            else -> Timer.DueIn(span)
        }

    /**
     * Fire [conn]'s next timer now, without moving the pair's clock: [conn]'s own clock runs ahead of
     * the pair's by the wait it skipped, from here on. A peer whose retransmissions a scenario times
     * itself is driven this way.
     */
    fun bringTimerForward(conn: QuicheConn): Timer =
        timer(conn).also { timer ->
            when (timer) {
                Timer.Unarmed -> Unit
                is Timer.DueIn -> {
                    leads[conn] = lead(conn) + timer.span
                    at(lead(conn)) { api.connOnTimeout(conn) }
                }
            }
        }

    /** How [conn] stands: open, or which way it ended. */
    fun ending(conn: QuicheConn): Ending =
        when {
            at(lead(conn)) { api.connIsTimedOut(conn) } -> Ending.IdleTimeout
            at(lead(conn)) { api.connPeerError(conn) } != null -> Ending.ClosedByPeer
            at(lead(conn)) { api.connLocalError(conn) } != null -> Ending.ClosedLocally
            at(lead(conn)) { api.connIsClosed(conn) } -> Ending.ClosedLocally
            else -> Ending.Open
        }

    /** How long until [conn]'s next timer falls due, or null when none is armed. */
    fun nextTimer(conn: QuicheConn): Duration? = at(lead(conn)) { api.connTimeout(conn) }

    /** Packets [conn] received after a gap in the packet numbers, as `quiche_conn_recv_gaps` counts them. */
    fun recvGaps(conn: QuicheConn): Long = at(lead(conn)) { api.connRecvGaps(conn) }

    /** Whether [conn]'s handshake has completed. */
    fun established(conn: QuicheConn): Boolean = at(lead(conn)) { api.connIsEstablished(conn) }

    /** Whether [conn]'s handshake is confirmed (RFC 9001 §4.1.2). */
    fun confirmed(conn: QuicheConn): Boolean = at(lead(conn)) { api.connIsHandshakeConfirmed(conn) }

    /** [conn] closes the connection with [error]; its CONNECTION_CLOSE is in its next [sends]. */
    fun closeWith(
        conn: QuicheConn,
        error: QuicError,
    ): Int = at(lead(conn)) { api.connClose(conn, error) }

    private val sendEnds = mutableMapOf<QuicheConn, Int>()

    /**
     * What [conn]'s last `quiche_conn_send` in [sends] answered: [QUICHE_ERR_DONE] when it had
     * nothing more to send, any other negative code when quiche refused to send at all.
     */
    fun lastSendEnd(conn: QuicheConn): Int = sendEnds[conn] ?: QUICHE_ERR_DONE

    /** What one `quiche_conn_send` gave: a datagram and the time quiche's pacer wants it on the wire, or nothing. */
    sealed interface Written {
        data object Nothing : Written

        class One(
            val datagram: Datagram,
            /** The send info's `at`, on the pair's clock ([nowNanos]). */
            val dueNanos: Long,
        ) : Written
    }

    /** The pair's clock as [conn]'s quiche reads it, in nanoseconds: the time its send infos are stamped on. */
    fun nowNanos(conn: QuicheConn): Long = (now + lead(conn)).inWholeNanoseconds

    /** One datagram [from] writes now, with its pacer's time; [Written.Nothing] when quiche has none (or refuses: [lastSendEnd]). */
    fun sendOne(from: QuicheConn): Written {
        val n = at(lead(from)) { api.connSend(from, datagram.nativeMemoryAccess!!.nativeAddress.toLong(), MAX_DATAGRAM, sendInfo) }
        if (n <= 0) {
            sendEnds[from] = n
            return Written.Nothing
        }
        val clientSide = if (from == client) api.sendInfoFromAddr(sendInfo) else api.sendInfoToAddr(sendInfo)
        datagram.position(0)
        return Written.One(Datagram(datagram.readByteArray(n), endpointAt(api.sockAddrPort(clientSide))), api.sendInfoAtNanos(sendInfo))
    }

    /** Every datagram [from] writes now, until quiche has nothing more to send (or refuses: [lastSendEnd]). */
    fun sends(from: QuicheConn): List<Datagram> =
        buildList {
            while (true) {
                val n = at(lead(from)) { api.connSend(from, datagram.nativeMemoryAccess!!.nativeAddress.toLong(), MAX_DATAGRAM, sendInfo) }
                if (n <= 0) {
                    sendEnds[from] = n
                    break
                }
                val clientSide = if (from == client) api.sendInfoFromAddr(sendInfo) else api.sendInfoToAddr(sendInfo)
                datagram.position(0)
                add(Datagram(datagram.readByteArray(n), endpointAt(api.sockAddrPort(clientSide))))
            }
        }

    /** Hand [datagrams] to [to], each on the path its endpoint names, as the network would. */
    fun deliver(
        datagrams: List<Datagram>,
        to: QuicheConn,
    ) {
        for (d in datagrams) {
            val info = if (to == client) clientRecvInfos[d.clientEndpoint] else serverRecvInfos[d.clientEndpoint]
            datagram.position(0)
            datagram.writeBytes(d.bytes)
            at(lead(to)) { api.connRecv(to, datagram.nativeMemoryAccess!!.nativeAddress.toLong(), d.bytes.size, info) }
        }
    }

    /** Exchange every datagram both ways, losing none, until neither side has anything to send. */
    fun settle() {
        repeat(MAX_SETTLE_ROUNDS) {
            val toServer = sends(client)
            val toClient = sends(server)
            if (toServer.isEmpty() && toClient.isEmpty()) return
            deliver(toServer, server)
            deliver(toClient, client)
        }
        error("the pair was still exchanging datagrams after $MAX_SETTLE_ROUNDS rounds")
    }

    /**
     * Rounds of: the client sends, [oneWay] passes, the server receives what [carries] lets through and
     * answers, [oneWay] passes, the client receives what [carries] lets through — until a round in which
     * neither side sends.
     */
    fun exchange(
        oneWay: Duration,
        carries: (Datagram) -> Boolean,
    ) {
        repeat(MAX_SETTLE_ROUNDS) {
            val toServer = sends(client)
            elapse(oneWay)
            deliver(toServer.filter(carries), server)
            val toClient = sends(server)
            elapse(oneWay)
            deliver(toClient.filter(carries), client)
            if (toServer.isEmpty() && toClient.isEmpty()) return
        }
        error("the pair was still exchanging datagrams after $MAX_SETTLE_ROUNDS rounds")
    }

    /**
     * Run the handshake, losing nothing and with [oneWay] on every leg (so quiche has a round trip to
     * time its PTOs by), until the client completes it. The client's Finished is then waiting in its
     * next [sends], for the scenario to deliver or lose.
     */
    fun handshakeUpToTheClientsFinished(oneWay: Duration = DEFAULT_PATH_LATENCY) {
        repeat(MAX_SETTLE_ROUNDS) {
            if (established(client)) return
            val toServer = sends(client)
            elapse(oneWay)
            deliver(toServer, server)
            val toClient = sends(server)
            elapse(oneWay)
            deliver(toClient, client)
        }
        error("the client had not completed its handshake after $MAX_SETTLE_ROUNDS rounds")
    }

    /** Issue [conn]'s peer the spare connection IDs it may hold, [atMost] of them. */
    fun issueSpareCids(
        conn: QuicheConn,
        atMost: Int = Int.MAX_VALUE,
    ) {
        var issued = 0
        while (issued++ < atMost && at(lead(conn)) { api.connScidsLeft(conn) } > 0L) {
            val scid = generateScid(bufferFactory, cidRandom)
            val token = bufferFactory.allocate(STATELESS_RESET_TOKEN_LEN)
            repeat(STATELESS_RESET_TOKEN_LEN) { token.writeByte(cidRandom.nextInt(256).toByte()) }
            token.resetForRead()
            val rc =
                at(lead(conn)) {
                    api.connNewScid(
                        conn,
                        scid.nativeMemoryAccess!!.nativeAddress.toLong(),
                        QUIC_ISSUED_CONN_ID_LEN,
                        token.nativeMemoryAccess!!.nativeAddress.toLong(),
                        true,
                        seqOut.nativeMemoryAccess!!.nativeAddress.toLong(),
                    )
                }
            scid.freeNativeMemory()
            token.freeNativeMemory()
            check(rc >= 0) { "quiche_conn_new_scid answered $rc" }
        }
    }

    /** The client probes the path from its [endpoint] to the server. */
    fun probe(endpoint: Int): ProbeOutcome =
        at(lead(client)) {
            api.connProbePath(client, clientAddrs[endpoint].address, clientAddrs[endpoint].length, serverAddr.address, serverAddr.length)
        }

    /**
     * The client probes the path from its [endpoint] with `quiche_conn_probe_path_with`: the path assumes
     * [initialRttNanos] until it has a sample, and its challenge leaves past the congestion window.
     */
    fun probeWith(
        endpoint: Int,
        initialRttNanos: Long,
    ): ProbeOutcome =
        at(lead(client)) {
            api.connProbePathWith(
                client,
                clientAddrs[endpoint].address,
                clientAddrs[endpoint].length,
                serverAddr.address,
                serverAddr.length,
                initialRttNanos,
            )
        }

    /** The client moves onto the path from its [endpoint]. */
    fun migrate(endpoint: Int): MigrateOutcome =
        at(lead(client)) {
            api.connMigrate(client, clientAddrs[endpoint].address, clientAddrs[endpoint].length, serverAddr.address, serverAddr.length)
        }

    /** [conn] (the client unless named) retires the destination connection ID with sequence number [seq]. */
    fun retireDcid(
        seq: Long,
        conn: QuicheConn = client,
    ): Int = at(lead(conn)) { api.connRetireDcid(conn, seq) }

    /** Spare destination connection IDs [conn] holds. */
    fun spareDcids(conn: QuicheConn): Long = at(lead(conn)) { api.connAvailableDcids(conn) }

    /**
     * How [conn] has ended: open, or the error it is closing or closed with. A connection that has sent or
     * received a CONNECTION_CLOSE is not open even while it drains, before quiche reports it closed.
     */
    fun closed(conn: QuicheConn): String {
        val local = at(lead(conn)) { api.connLocalError(conn) }
        val peer = at(lead(conn)) { api.connPeerError(conn) }
        val isClosed = at(lead(conn)) { api.connIsClosed(conn) }
        return if (!isClosed && local == null && peer == null) {
            "open"
        } else {
            "${if (isClosed) "closed" else "closing"} local=$local peer=$peer"
        }
    }

    /** Source connection IDs [conn] may still issue to its peer. */
    fun scidsLeft(conn: QuicheConn): Long = at(lead(conn)) { api.connScidsLeft(conn) }

    /** How many paths [conn]'s path table holds. */
    fun pathCount(conn: QuicheConn): Long = at(lead(conn)) { api.connStats(conn)?.pathsCount } ?: 0L

    /** [conn]'s path table, one entry per path: validation state, active, packets sent / lost / retransmitted. */
    fun pathTable(conn: QuicheConn): String =
        (0 until pathCount(conn)).joinToString(" ") { idx ->
            val st = at(lead(conn)) { api.connPathStats(conn, idx) }
            "[$idx state=${st?.validationState} active=${st?.active} sent=${st?.sent} lost=${st?.lost} retrans=${st?.retrans}]"
        }

    /** Drain [conn]'s path events, each with the client endpoint it concerns. */
    fun pathEvents(conn: QuicheConn): List<String> =
        buildList {
            while (true) {
                val type =
                    at(lead(conn)) {
                        api.connPathEventNext(
                            conn,
                            eventLocal.nativeMemoryAccess!!.nativeAddress.toLong(),
                            eventLocalLen.nativeMemoryAccess!!.nativeAddress.toLong(),
                            eventPeer.nativeMemoryAccess!!.nativeAddress.toLong(),
                            eventPeerLen.nativeMemoryAccess!!.nativeAddress.toLong(),
                        )
                    } ?: break
                val clientSide = if (conn == client) eventLocal else eventPeer
                add("$type(endpoint ${endpointAt(api.sockAddrPort(clientSide.nativeMemoryAccess!!.nativeAddress.toLong()))})")
            }
        }

    fun streamSend(
        conn: QuicheConn,
        stream: QuicStreamId,
        text: String,
        fin: Boolean,
    ): Int {
        val buf = bufferFactory.allocate(maxOf(text.length, 1))
        return try {
            buf.writeString(text, Charset.UTF8)
            buf.resetForRead()
            at(lead(conn)) { api.connStreamSend(conn, stream, buf.nativeMemoryAccess!!.nativeAddress.toLong(), text.length, fin).result }
        } finally {
            buf.freeNativeMemory()
        }
    }

    /** What [conn] can read from [stream] now: its bytes, and whether they end it. */
    fun readAll(
        conn: QuicheConn,
        stream: QuicStreamId,
    ): Pair<Int, Boolean> = readText(conn, stream).let { (text, fin) -> text.length to fin }

    /** What [conn] can read from [stream] now, as text, and whether it ends the stream. */
    fun readText(
        conn: QuicheConn,
        stream: QuicStreamId,
    ): Pair<String, Boolean> {
        val buf: PlatformBuffer = bufferFactory.allocate(READ_CHUNK)
        try {
            val text = StringBuilder()
            while (true) {
                buf.position(0)
                when (
                    val r =
                        at(
                            lead(conn),
                        ) { api.connStreamRecv(conn, stream, buf.nativeMemoryAccess!!.nativeAddress.toLong(), READ_CHUNK) }
                ) {
                    is StreamRecvResult.Data -> {
                        buf.position(0)
                        text.append(buf.readString(r.bytesRead, Charset.UTF8))
                        if (r.fin) return text.toString() to true
                        if (r.bytesRead == 0) return text.toString() to false
                    }
                    else -> return text.toString() to false
                }
            }
        } finally {
            buf.freeNativeMemory()
        }
    }

    private fun endpointAt(port: Int): Int =
        (port - CLIENT_PORT).also { check(it in clientAddrs.indices) { "port $port is none of the client's endpoints" } }

    fun close() {
        api.connFree(client)
        api.connFree(server)
        api.configFree(clientCfg)
        api.configFree(serverCfg)
        (clientRecvInfos + serverRecvInfos).forEach(api::recvInfoFree)
        api.sendInfoFree(sendInfo)
        listOf(datagram, seqOut, eventLocal, eventLocalLen, eventPeer, eventPeerLen).forEach { it.freeNativeMemory() }
        clientAddrs.forEach { it.free() }
        serverAddr.free()
    }

    companion object {
        suspend fun open(
            env: MigrationSimEnv,
            options: QuicOptions = migrationSimOptions(),
            /** Extra settings for the client's config, applied before it connects (e.g. `configLogKeys`). */
            configureClient: (QuicheConfig) -> Unit = {},
            /** The server's options, when a scenario's two ends differ. */
            serverOptions: QuicOptions = options,
        ): QuichePairByHand =
            QuichePairByHand(
                env,
                (0 until CLIENT_ENDPOINTS).map { UdpSocket.resolve("127.0.0.1", CLIENT_PORT + it) },
                UdpSocket.resolve("127.0.0.1", SERVER_PORT),
                options,
                serverOptions,
                configureClient,
            )

        /** Local endpoints the client can send from: the one it connects from, then one per probe. */
        const val CLIENT_ENDPOINTS = 16

        private const val QUICHE_V1 = 0x00000001
        private const val CLIENT_PORT = 42900
        private const val SERVER_PORT = 42950
        private const val CLIENT_SEED = 0x434C49L
        private const val SERVER_SEED = 0x535256L
        private const val CID_SEED = 0x434944L
        private const val MAX_DATAGRAM = 1500
        private const val READ_CHUNK = 65536
        private const val SOCKADDR_STORAGE_SIZE = 128
        private const val STATELESS_RESET_TOKEN_LEN = 16
        private const val MAX_SETTLE_ROUNDS = 64
    }
}
