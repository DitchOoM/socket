package com.ditchoom.socket.quic

/**
 * Shared (JVM + Android) [RebindingProxy] for [QuicPassiveMigrationTestSuite]. Lives in
 * `sharedJvmTestProtocol` so the JVM ([QuicPassiveMigrationTests]) and Android
 * ([AndroidQuicPassiveMigrationTests]) members share one implementation.
 *
 * Backed by a non-blocking [SelectorDatagramRelay]: both directions are pure pass-through; [rebind] swaps
 * the upstream for a fresh source port. Because the relay closes the old upstream on its own select()
 * thread (never while blocked in a read), neither rebind nor teardown can hit the `IOException: Success`
 * close race — see [SelectorDatagramRelay].
 */
internal class DatagramChannelRebindingProxy(
    serverPort: Int,
) : RebindingProxy {
    // lateinit + init{}: the pass-through callbacks reference the relay, so an inferred `val` whose type
    // comes from those same callbacks would be a type-inference cycle.
    private lateinit var relay: SelectorDatagramRelay

    override val proxyPort: Int get() = relay.proxyPort

    init {
        relay =
            SelectorDatagramRelay(
                serverPort = serverPort,
                maxDatagram = 2048,
                onClientToServer = { buf, _ -> relay.writeToServer(buf) },
                onServerToClient = { buf, _ -> relay.writeToClient(buf) },
            )
        relay.start()
    }

    override fun rebind() = relay.rebindUpstream()

    override suspend fun close() = relay.close()
}
