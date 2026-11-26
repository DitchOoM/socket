package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.udp.SocketAddressCodec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One-way latency every simulated path carries unless a scenario says otherwise. 60 ms one way (120 ms round
 * trip) is an ordinary cellular path and is past quiche's ~75 ms abandoned-path loss timer, so the default
 * exercises that timer; a scenario that wants RTT near zero has to ask for it.
 */
internal val DEFAULT_PATH_LATENCY = 60.milliseconds

/**
 * A path written into native memory as a NUL-terminated C string, for quiche's `*_from_pem_file` calls. Named
 * distinctly from the `commonJvmMain` helper so the JVM compilation, which sees both, has no ambiguity.
 */
internal fun simNullTerminated(
    path: String,
    factory: BufferFactory,
): PlatformBuffer {
    val buf = factory.allocate(path.length + 1)
    buf.writeString(path, Charset.UTF8)
    buf.writeByte(0)
    buf.resetForRead()
    return buf
}

/**
 * Sim options: TLS verification off (self-signed fixture cert), idle long enough not to race the scenario.
 *
 * [migration] defaults to [MigrationPolicy.Manual]; pass [MigrationPolicy.Automatic] with a
 * [NetworkMonitorSource.Supplied] scriptable monitor to put the reactor in charge. [networkMonitor] defaults to a
 * supplied [NetworkMonitor.AlwaysAvailable], so the harness never resolves a real platform monitor.
 */
internal fun migrationSimOptions(
    idleTimeout: Duration = 120.seconds,
    keepAliveInterval: Duration? = null,
    migration: MigrationPolicy = MigrationPolicy.Manual,
    networkMonitor: NetworkMonitorSource = NetworkMonitorSource.Supplied(NetworkMonitor.AlwaysAvailable),
    trace: QuicTraceCapture? = null,
): QuicOptions =
    QuicOptions(
        alpnProtocols = listOf("migsim"),
        verifyPeer = false,
        idleTimeout = idleTimeout,
        keepAliveInterval = keepAliveInterval,
        migration = migration,
        networkMonitor = networkMonitor,
        trace = trace,
    )

/**
 * What a simulated run takes from the platform rather than the scenario: the `libquiche` binding to drive, this
 * target's test certificates, the C `sockaddr` layout its OS uses, and the binding's caller-entropy pin (which
 * seeds quiche's own random draws from the sim's seed).
 */
internal class MigrationSimEnv(
    val api: QuicheApi,
    val certChainPath: String,
    val privKeyPath: String,
    val codec: SocketAddressCodec,
    val randomPin: QuicheRandomPin,
)
