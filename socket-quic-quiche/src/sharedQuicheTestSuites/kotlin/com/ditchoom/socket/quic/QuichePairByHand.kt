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
 * timer falling due on the way. Loss is staged by not delivering a datagram; quiche declares it lost by
 * packet threshold (RFC 9002 §6.1.1) once three later packets are acknowledged, or by its timers.
 */
internal class QuichePairByHand private constructor(
    env: MigrationSimEnv,
    clientEndpoints: List<SocketAddress>,
    serverSocket: SocketAddress,
    options: QuicOptions,
) {
    /** A datagram as the network carries it: its bytes, and the client endpoint it leaves or reaches. */
    class Datagram(
        val bytes: ByteArray,
        val clientEndpoint: Int,
    )

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
    private val eventLocal = bufferFactory.allocate(QuicheDriver.SOCKADDR_STORAGE_SIZE)
    private val eventLocalLen = bufferFactory.allocate(4)
    private val eventPeer = bufferFactory.allocate(QuicheDriver.SOCKADDR_STORAGE_SIZE)
    private val eventPeerLen = bufferFactory.allocate(4)
    private val cidRandom = Random(CID_SEED)
    private var now: Duration = Duration.ZERO
    val client: QuicheConn
    val server: QuicheConn

    init {
        listOf(serverCfg to QuicRole.Server, clientCfg to QuicRole.Client).forEach { (cfg, role) ->
            val alpn = encodeAlpnList(options.alpnProtocols, bufferFactory)
            api.configSetApplicationProtos(cfg, alpn.nativeMemoryAccess!!.nativeAddress.toLong(), alpn.remaining())
            alpn.freeNativeMemory()
            applyQuicOptions(options, SimQuicConfigCalls(api, cfg), role)
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
                    QUIC_MAX_CONN_ID_LEN,
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
                    QUIC_MAX_CONN_ID_LEN,
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

    /** Run [block] with quiche's clock on this thread pinned to [now]. */
    private inline fun <T> at(block: () -> T): T {
        api.setThreadVirtualTimeNanos(now.inWholeNanoseconds)
        try {
            return block()
        } finally {
            api.clearThreadVirtualTime()
        }
    }

    /** Move the clock on by [span], firing each quiche timer of either connection as it falls due. */
    fun elapse(span: Duration) {
        val until = now + span
        while (true) {
            val (due, conn) =
                listOf(client, server)
                    .mapNotNull { conn -> at { api.connTimeout(conn) }?.let { now + it to conn } }
                    .minByOrNull { it.first }
                    ?.takeIf { it.first <= until }
                    ?: break
            now = due
            at { api.connOnTimeout(conn) }
        }
        now = until
    }

    /** Every datagram [from] writes now, until quiche has nothing more to send. */
    fun sends(from: QuicheConn): List<Datagram> =
        buildList {
            while (true) {
                val n = at { api.connSend(from, datagram.nativeMemoryAccess!!.nativeAddress.toLong(), MAX_DATAGRAM, sendInfo) }
                if (n <= 0) break
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
            at { api.connRecv(to, datagram.nativeMemoryAccess!!.nativeAddress.toLong(), d.bytes.size, info) }
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

    /** Issue [conn]'s peer every spare connection ID it may hold. */
    fun issueSpareCids(conn: QuicheConn) {
        while (at { api.connScidsLeft(conn) } > 0L) {
            val scid = generateScid(bufferFactory, cidRandom)
            val token = bufferFactory.allocate(QuicheDriver.STATELESS_RESET_TOKEN_LEN)
            repeat(QuicheDriver.STATELESS_RESET_TOKEN_LEN) { token.writeByte(cidRandom.nextInt(256).toByte()) }
            token.resetForRead()
            val rc =
                at {
                    api.connNewScid(
                        conn,
                        scid.nativeMemoryAccess!!.nativeAddress.toLong(),
                        QUIC_MAX_CONN_ID_LEN,
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
        at { api.connProbePath(client, clientAddrs[endpoint].address, clientAddrs[endpoint].length, serverAddr.address, serverAddr.length) }

    /** The client moves onto the path from its [endpoint]. */
    fun migrate(endpoint: Int): MigrateOutcome =
        at { api.connMigrate(client, clientAddrs[endpoint].address, clientAddrs[endpoint].length, serverAddr.address, serverAddr.length) }

    /** The client retires the destination connection ID with sequence number [seq]. */
    fun retireDcid(seq: Long): Int = at { api.connRetireDcid(client, seq) }

    /** Spare destination connection IDs [conn] holds. */
    fun spareDcids(conn: QuicheConn): Long = at { api.connAvailableDcids(conn) }

    /** How [conn] has ended: open, or the error it closed with. */
    fun closed(conn: QuicheConn): String =
        if (!at { api.connIsClosed(conn) }) "open" else "local=${at { api.connLocalError(conn) }} peer=${at { api.connPeerError(conn) }}"

    /** Source connection IDs [conn] may still issue to its peer. */
    fun scidsLeft(conn: QuicheConn): Long = at { api.connScidsLeft(conn) }

    /** How many paths [conn]'s path table holds. */
    fun pathCount(conn: QuicheConn): Long = at { api.connStats(conn)?.pathsCount } ?: 0L

    /** [conn]'s path table, one entry per path: validation state, active, packets sent / lost / retransmitted. */
    fun pathTable(conn: QuicheConn): String =
        (0 until pathCount(conn)).joinToString(" ") { idx ->
            val st = at { api.connPathStats(conn, idx) }
            "[$idx state=${st?.validationState} active=${st?.active} sent=${st?.sent} lost=${st?.lost} retrans=${st?.retrans}]"
        }

    /** Drain [conn]'s path events, each with the client endpoint it concerns. */
    fun pathEvents(conn: QuicheConn): List<String> =
        buildList {
            while (true) {
                val type =
                    at {
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
            at { api.connStreamSend(conn, stream, buf.nativeMemoryAccess!!.nativeAddress.toLong(), text.length, fin).result }
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
                when (val r = at { api.connStreamRecv(conn, stream, buf.nativeMemoryAccess!!.nativeAddress.toLong(), READ_CHUNK) }) {
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
        ): QuichePairByHand =
            QuichePairByHand(
                env,
                (0 until CLIENT_ENDPOINTS).map { UdpSocket.resolve("127.0.0.1", CLIENT_PORT + it) },
                UdpSocket.resolve("127.0.0.1", SERVER_PORT),
                options,
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
        private const val MAX_SETTLE_ROUNDS = 64
    }
}
