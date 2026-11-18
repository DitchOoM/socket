package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.core.conn.HandshakeRule
import com.ditchoom.socket.quic.core.conn.Offer
import com.ditchoom.socket.quic.core.conn.freeUnbuilt
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Where every core connect's and serve's setup runs: opening sockets, reading trust anchors, loading the native library
 * and probing the route block, so none of it runs on a caller's thread, which may be a main thread. One setup at a time:
 * a herd of connects queues here and keeps one IO worker busy, instead of waking one worker per connect.
 */
private val laneDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/** The context of the setup lane: one thread at a time, whatever runs on it. */
internal val setupLane: CoroutineContext = laneDispatcher

/**
 * Code on the setup lane, and the only way to the quiche client configs connects share. Its one instance is handed to
 * the block [run] runs and to nothing else.
 *
 * quiche's `connect` takes its config exclusively (`&mut Config`) and reads it while it builds the connection, so a
 * shared config is used by one connect at a time: [withClientConfig] hands it to a block that cannot suspend, so no
 * other lane block, which is the only other code that reaches it, runs in between. A connection keeps nothing of the
 * config but a reference on its TLS context, which quiche takes at connect; the config is freed once [CONFIGS_KEPT]
 * newer plans have been used, whatever connections it made are still open.
 */
internal class OnSetupLane private constructor() {
    /** The configs kept, least recently used first. */
    private val configs = LinkedHashMap<ConfigKey, SharedConfig>()

    /** Runs [use] with the client config of [options] and [handshake] on [api], built on the first use of its plan. */
    fun <T> withClientConfig(
        api: QuicheApi,
        options: QuicOptions,
        handshake: HandshakeRule,
        factory: BufferFactory,
        use: (SharedConfig) -> T,
    ): T {
        val key = ConfigKey(api, clientConfigPlan(options, handshake))
        val config = configs.remove(key) ?: SharedConfig(buildClientConfig(api, options, handshake, factory))
        configs[key] = config
        while (configs.size > CONFIGS_KEPT) {
            val oldest = configs.keys.first()
            configs.remove(oldest)?.let { oldest.api.configFree(it.config) }
        }
        return use(config)
    }

    /** A config connects share, lent to one lane block at a time. */
    class SharedConfig(
        val config: QuicheConfig,
    )

    /** A plan on the api that built its config: a handle of one api means nothing to another. */
    private data class ConfigKey(
        val api: QuicheApi,
        val plan: ClientConfigPlan,
    )

    companion object {
        private val lane = OnSetupLane()

        /** Runs [block] on the setup lane. */
        suspend fun <T> run(block: suspend OnSetupLane.() -> T): T = withContext(setupLane) { lane.block() }

        /** The client configs kept: one per distinct plan in use, and the bound on what a process holds. */
        const val CONFIGS_KEPT = 16
    }
}

/** A client connection set up and started on the [setupLane]; its handshake is still to complete. */
internal class StartedClient(
    val client: CoreClient,
    private val offer: Offer,
) {
    /**
     * On the caller's own context, so its early data never runs on the lane: parks until the handshake completes,
     * running the [resumption]'s early-data block where the offer says.
     */
    suspend fun established(
        resumption: QuicResumption,
        timeout: Duration,
    ): CoreClient {
        val connection = client.connection
        connection.runEarlyData(resumption, offer) { withTimeout(timeout) { connection.established() } }
        return client
    }

    /** Ends a connection whose connect failed or was cancelled after it started. */
    fun abandon() = client.connection.closeNow(QuicError.NoError)
}

/**
 * How far a connect's setup got, and so who ends what it made if it fails or is cancelled: each thing it made has one
 * releaser at a time. Until the connection starts that is the connect, itself and then through the connection's
 * release; once it starts, the connection, whose release on its loop frees everything.
 */
internal sealed interface ClientSetup {
    /** The connect frees [memory]. */
    class Unstarted(
        val memory: EdgeMemory,
    ) : ClientSetup

    /** The connect also frees the peer's address, encoded for quiche. */
    class PeerEncoded(
        val memory: EdgeMemory,
        val peer: EncodedSockAddr,
    ) : ClientSetup

    /** The connect also frees its own address, encoded for quiche. */
    class Encoded(
        val memory: EdgeMemory,
        val peer: EncodedSockAddr,
        val local: EncodedSockAddr,
    ) : ClientSetup

    /** The connect also frees quiche's connection, which no connection of the core holds yet. */
    class Connected(
        val memory: EdgeMemory,
        val peer: EncodedSockAddr,
        val local: EncodedSockAddr,
        val quiche: QuicheConnRef,
    ) : ClientSetup

    /** The connection holds all of it, unstarted: the connect releases it there, which also closes its socket. */
    class Built(
        val parts: ClientParts,
    ) : ClientSetup

    /** The connect only ends the connection; the connection frees the memory once its loop released it. */
    class Started(
        val started: StartedClient,
    ) : ClientSetup

    /** The connect returned the connection to its caller. */
    data object Established : ClientSetup
}

/** Releases what a connect that failed or was cancelled at this step made, each thing once. */
internal fun ClientSetup.endFailed() =
    when (this) {
        is ClientSetup.Unstarted -> memory.freeAll()
        is ClientSetup.PeerEncoded -> {
            peer.free()
            memory.freeAll()
        }
        is ClientSetup.Encoded -> {
            peer.free()
            local.free()
            memory.freeAll()
        }
        is ClientSetup.Connected -> {
            quiche.freeUnbuilt()
            peer.free()
            local.free()
            memory.freeAll()
        }
        is ClientSetup.Built -> parts.releaseUnstarted()
        is ClientSetup.Started -> started.abandon()
        is ClientSetup.Established -> Unit
    }
