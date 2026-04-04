package com.ditchoom.socket.quic

// Android shares the JVM implementation (DatagramChannel + QuicheApi via JNI/FFM)
actual fun defaultQuicEngine(): QuicEngine = defaultJvmQuicEngine()

/** Visible from androidMain — delegates to the commonJvmMain implementation. */
internal fun defaultJvmQuicEngine(): QuicEngine {
    // Same factory function as JVM — loadQuicheApi picks JNI or FFM
    return object : QuicEngine {
        private val delegate = loadQuicheApi()

        override suspend fun connect(
            hostname: String,
            port: Int,
            quicOptions: QuicOptions,
            connectionOptions: com.ditchoom.socket.ConnectionOptions,
            timeout: kotlin.time.Duration,
        ): QuicConnection {
            TODO("Android QUIC — same as JVM, pending sockaddr allocation")
        }

        override fun close() {}
    }
}
