package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.sim.resolve
import com.ditchoom.socket.udp.UdpSocket
import kotlin.random.Random

/**
 * A real client/server quiche pair with no driver, no pipe and no clock: every datagram either side
 * writes is returned to the test, which decides whether the other side receives it. For a scenario whose
 * outcome depends on the order of sends, losses and stream writes inside one quiche connection — an order
 * the sim's drivers settle by thread timing — this states the order instead.
 *
 * Loss is staged by not delivering a datagram; quiche declares it lost by packet threshold (RFC 9002
 * §6.1.1) once three later packets are acknowledged, so no timer has to fire.
 */
internal class QuichePairByHand private constructor(
    env: MigrationSimEnv,
    clientSocket: SocketAddress,
    serverSocket: SocketAddress,
    options: QuicOptions,
) {
    private val api = env.api
    private val bufferFactory = BufferFactory.network()
    private val clientAddr = env.codec.encodeToNative(clientSocket, bufferFactory)
    private val serverAddr = env.codec.encodeToNative(serverSocket, bufferFactory)
    private val clientCfg = api.configNew(QUICHE_V1)
    private val serverCfg = api.configNew(QUICHE_V1)
    private val datagram = bufferFactory.allocate(MAX_DATAGRAM)
    private val clientRecvInfo = api.recvInfoNew(serverAddr.address, serverAddr.length, clientAddr.address, clientAddr.length)
    private val serverRecvInfo = api.recvInfoNew(clientAddr.address, clientAddr.length, serverAddr.address, serverAddr.length)
    private val sendInfo = api.sendInfoNew()
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
        client =
            api.connect(
                serverName.nativeMemoryAccess!!.nativeAddress.toLong(),
                "localhost".length,
                clientScid.nativeMemoryAccess!!.nativeAddress.toLong(),
                QUIC_MAX_CONN_ID_LEN,
                clientAddr.address,
                clientAddr.length,
                serverAddr.address,
                serverAddr.length,
                clientCfg,
            )
        serverName.freeNativeMemory()
        clientScid.freeNativeMemory()
        val serverScid = generateScid(bufferFactory, Random(SERVER_SEED))
        server =
            api.accept(
                serverScid.nativeMemoryAccess!!.nativeAddress.toLong(),
                QUIC_MAX_CONN_ID_LEN,
                0L,
                0,
                serverAddr.address,
                serverAddr.length,
                clientAddr.address,
                clientAddr.length,
                serverCfg,
            )
        serverScid.freeNativeMemory()
    }

    /** Every datagram [from] writes now, until quiche has nothing more to send. */
    fun sends(from: QuicheConn): List<ByteArray> =
        buildList {
            while (true) {
                val n = api.connSend(from, datagram.nativeMemoryAccess!!.nativeAddress.toLong(), MAX_DATAGRAM, sendInfo)
                if (n <= 0) break
                datagram.position(0)
                add(datagram.readByteArray(n))
            }
        }

    /** Hand [datagrams] to [to], as the network would. */
    fun deliver(
        datagrams: List<ByteArray>,
        to: QuicheConn,
    ) {
        val info = if (to == client) clientRecvInfo else serverRecvInfo
        for (d in datagrams) {
            datagram.position(0)
            datagram.writeBytes(d)
            api.connRecv(to, datagram.nativeMemoryAccess!!.nativeAddress.toLong(), d.size, info)
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
            api.connStreamSend(conn, stream, buf.nativeMemoryAccess!!.nativeAddress.toLong(), text.length, fin).result
        } finally {
            buf.freeNativeMemory()
        }
    }

    /** What [conn] can read from [stream] now: its bytes, and whether they end it. */
    fun readAll(
        conn: QuicheConn,
        stream: QuicStreamId,
    ): Pair<Int, Boolean> {
        val buf: PlatformBuffer = bufferFactory.allocate(READ_CHUNK)
        try {
            var total = 0
            while (true) {
                when (val r = api.connStreamRecv(conn, stream, buf.nativeMemoryAccess!!.nativeAddress.toLong(), READ_CHUNK)) {
                    is StreamRecvResult.Data -> {
                        total += r.bytesRead
                        if (r.fin) return total to true
                        if (r.bytesRead == 0) return total to false
                    }
                    else -> return total to false
                }
            }
        } finally {
            buf.freeNativeMemory()
        }
    }

    fun close() {
        api.connFree(client)
        api.connFree(server)
        api.configFree(clientCfg)
        api.configFree(serverCfg)
        api.recvInfoFree(clientRecvInfo)
        api.recvInfoFree(serverRecvInfo)
        api.sendInfoFree(sendInfo)
        datagram.freeNativeMemory()
        clientAddr.free()
        serverAddr.free()
    }

    companion object {
        suspend fun open(
            env: MigrationSimEnv,
            options: QuicOptions = migrationSimOptions(),
        ): QuichePairByHand =
            QuichePairByHand(env, UdpSocket.resolve("127.0.0.1", CLIENT_PORT), UdpSocket.resolve("127.0.0.1", SERVER_PORT), options)

        private const val QUICHE_V1 = 0x00000001
        private const val CLIENT_PORT = 42900
        private const val SERVER_PORT = 42901
        private const val CLIENT_SEED = 0x434C49L
        private const val SERVER_SEED = 0x535256L
        private const val MAX_DATAGRAM = 1500
        private const val READ_CHUNK = 65536
        private const val MAX_SETTLE_ROUNDS = 64
    }
}
