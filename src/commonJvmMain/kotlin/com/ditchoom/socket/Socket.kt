package com.ditchoom.socket

import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun ClientSocket.Companion.allocate(config: TransportConfig): ClientToServerSocket =
    if (useAsyncChannels) {
        try {
            AsyncClientSocket(config)
        } catch (t: Throwable) {
            // It's possible Android OS version is too old to support AsyncSocketChannel
            NioClientSocket(useNioBlocking, config)
        }
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

actual fun ServerSocket.Companion.allocate(config: TransportConfig): ServerSocket = AsyncServerSocket(config)
