@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic.sim

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheDriver
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Everything one simulation run leaves behind for assertions: the trace plus the stub's counters. */
internal class QuicSimRun(
    val trace: SimTrace,
    val api: StubQuicheApi,
)

/**
 * Run one [SimFixture] against a fresh [QuicheDriver] wired entirely onto the virtual-time seams
 * (W1: `driverContext = EmptyCoroutineContext`, W2: [SimClock]), record the observation trace, and
 * tear everything down. Callable repeatedly inside one `runTest` — trace timestamps are relative to
 * the run's own t0, so consecutive runs of the same fixture must produce `==` traces (the
 * determinism bar).
 *
 * [clientMode] = true starts the driver's real UDP reader loop against the [TimelineUdpChannel]
 * (new ground in W2 — no pre-existing test ran the reader loop on a scripted channel); false keeps
 * the classic command-only driver for pure timer fixtures.
 */
internal suspend fun TestScope.runQuicSim(
    fixture: SimFixture,
    keepAliveInterval: Duration? = null,
    clientMode: Boolean = false,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
    configureApi: StubQuicheApi.() -> Unit = {},
): QuicSimRun {
    val t0 = testScheduler.currentTime
    val trace = SimTrace { (testScheduler.currentTime - t0).milliseconds }
    val api = StubQuicheApi().apply(configureApi)
    // Chain — don't overwrite — hooks a configureApi installed (the W5 fuzz harness uses them to
    // model recv→ACK / PING→datagram send pressure); the trace stamp always records first.
    val configuredAckHook = api.onAckEliciting
    val configuredRecvHook = api.onConnRecv
    api.onAckEliciting = {
        trace.record(Observed.KeepAlivePing(trace.now()))
        configuredAckHook?.invoke()
    }
    api.onConnRecv = { len ->
        trace.record(Observed.DatagramFed(trace.now(), len))
        configuredRecvHook?.invoke(len)
    }
    val udp = TimelineUdpChannel(trace)
    val monitor = SimNetworkMonitor(initial = NetworkAvailability.AVAILABLE)
    val liveness = SimLiveness(trace)
    val clock = SimClock(testScheduler)
    val driver =
        QuicheDriver(
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = udp,
            clientMode = clientMode,
            isServer = false,
            keepAliveInterval = keepAliveInterval,
            clock = clock,
            driverContext = EmptyCoroutineContext,
        )

    // Child scope standing in for the production connection scope: it owns the driver loop, the
    // clientMode reader loop, and the trace collectors, and is cancelled at the end of the run —
    // otherwise runTest would wait forever on the reader parked in TimelineUdpChannel.receive().
    val simScope = CoroutineScope(coroutineContext + Job())
    simScope.launch {
        driver.state.collect { state ->
            trace.record(Observed.StateChange(trace.now(), state))
            if (state is QuicConnectionState.Closed) {
                state.error?.let { trace.record(Observed.ErrorSurfaced(trace.now(), it)) }
            }
        }
    }
    simScope.launch {
        driver.pathState.drop(1).collect { trace.record(Observed.PathStateChange(trace.now(), it)) }
    }
    simScope.launch {
        monitor.networkId.drop(1).collect { trace.record(Observed.NetworkChanged(trace.now(), it)) }
    }
    simScope.launch {
        monitor.availability.drop(1).collect { trace.record(Observed.AvailabilityChanged(trace.now(), it)) }
    }
    testScheduler.runCurrent() // collectors subscribed (initial StateChange recorded) before the driver starts

    driver.start(simScope)
    testScheduler.runCurrent() // initial flush: Handshaking -> Established under the stub, at t0

    with(SimTimeline(fixture)) { run(SimHarness(udp, monitor, liveness, clock, trace)) }

    driver.destroy() // idempotent when the timeline already closed the driver (e.g. idle close)
    simScope.cancel()
    testScheduler.advanceUntilIdle()
    return QuicSimRun(trace, api)
}
