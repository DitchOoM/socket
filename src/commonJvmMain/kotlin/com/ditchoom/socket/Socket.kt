package com.ditchoom.socket

import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio.NioServerSocket
import com.ditchoom.socket.nio.SelectorServerSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun ClientSocket.Companion.allocate(config: TransportConfig): ClientToServerSocket =
    // Chosen up front: without NIO2 constructing AsyncClientSocket succeeds and only open() fails.
    if (useAsyncChannels && nio2Available) {
        AsyncClientSocket(config)
    } else {
        NioClientSocket(useNioBlocking, config)
    }

// Per-JVM-process knobs for which client-socket implementation `allocate()`
// returns: NIO2 async channel (the default) vs NIO blocking vs NIO selector.
//
// ⚠ Process-wide mutable state. The Simple{Nio,NioNonBlocking,Async}SocketTests
// classes flip these to verify all three paths against the same SimpleSocketTests
// contract. ThreadLocal can't substitute here — `runTestNoTimeSkipping`'s
// `withContext(Dispatchers.Default.limitedParallelism(1))` switches off the
// JUnit setup thread, so a ThreadLocal set in `@BeforeTest` wouldn't be
// visible from the dispatcher thread that actually calls `allocate()`. (We
// considered `ThreadLocal.asContextElement()` propagation; the change ripple
// through every test entry point isn't worth it for a JVM-test-only knob.)
//
// Durability:
//   * SimpleNio{Blocking,NonBlocking}SocketTests do @BeforeTest/@AfterTest
//     save-and-restore so the flip doesn't leak across test classes.
//   * Gradle's JVM test task is pinned to maxParallelForks=1 in build.gradle.kts
//     so within-process tests run sequentially and the save/restore is enough.
//   * Don't flip `maxParallelForks > 1` without first migrating these flags
//     to a CoroutineContext.Element or a per-call factory.
var useAsyncChannels = true
var useNioBlocking = false

actual fun ServerSocket.Companion.allocate(config: TransportConfig): ServerSocket = serverSocketFor(config, serverBacking())

/** Which listener implementation `allocate` returns. */
internal sealed interface ServerBacking {
    /** NIO2 asynchronous channels. */
    data object Nio2 : ServerBacking

    /** Plain NIO whose accepts and reads wait on the shared selector loops. */
    data object NioSelector : ServerBacking

    /** Plain NIO that parks a `Dispatchers.IO` thread per listener and per connection read. */
    data object NioBlocking : ServerBacking
}

/**
 * NIO2 wherever the runtime has it, whatever `useAsyncChannels` and `useNioBlocking` say (they choose the
 * client). Without NIO2 (Android below API 26) plain NIO: selector loops, unless `useNioBlocking` asks for blocking.
 */
internal fun serverBacking(): ServerBacking =
    when {
        nio2Available -> ServerBacking.Nio2
        useNioBlocking -> ServerBacking.NioBlocking
        else -> ServerBacking.NioSelector
    }

internal fun serverSocketFor(
    config: TransportConfig,
    backing: ServerBacking,
): ServerSocket =
    when (backing) {
        ServerBacking.Nio2 -> AsyncServerSocket(config)
        ServerBacking.NioBlocking -> NioServerSocket(config)
        ServerBacking.NioSelector -> SelectorServerSocket(config)
    }

/** Whether this runtime has NIO2's asynchronous channels (every JVM; Android from API 26). Probed by name. */
internal val nio2Available: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching { Class.forName("java.nio.channels.AsynchronousServerSocketChannel") }.isSuccess
}
