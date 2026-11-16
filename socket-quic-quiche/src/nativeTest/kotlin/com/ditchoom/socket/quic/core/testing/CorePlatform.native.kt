@file:OptIn(ObsoleteWorkersApi::class)

package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.CinteropQuicheApi
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

// The platform calls the shared core suites (src/coreTest) make, on Kotlin/Native.

internal fun platformQuicheApi(): QuicheApi = CinteropQuicheApi

internal fun platformTlsConfig(): QuicTlsConfig = nativeCoreTls()

/** The server certificate the shared core suites serve: Apple and Linux find their test files differently. */
internal expect fun nativeCoreTls(): QuicTlsConfig

internal fun currentThreadName(): String = Worker.current.name

/** Runs [block] on a new worker named [name] and returns once it ended. */
internal fun runOnANewThread(
    name: String,
    block: () -> Unit,
) {
    val worker = Worker.start(name = name)
    worker.execute(TransferMode.SAFE, { block }) { it() }.result
    worker.requestTermination().result
}
