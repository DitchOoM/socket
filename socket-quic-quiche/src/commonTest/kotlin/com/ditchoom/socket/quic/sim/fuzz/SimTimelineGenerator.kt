package com.ditchoom.socket.quic.sim.fuzz

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.quic.sim.SimError
import com.ditchoom.socket.quic.sim.SimEvent
import com.ditchoom.socket.quic.sim.SimFixture
import com.ditchoom.socket.quic.sim.fixtures.SIM_IDLE_TIMEOUT
import com.ditchoom.socket.quic.sim.fixtures.SIM_KEEPALIVE_INTERVAL
import com.ditchoom.socket.transport.Liveness
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Relative weights of the adversarial event families [SimTimelineGenerator] draws from
 * (RFC_DETERMINISTIC_SIMULATION.md §6 item 1). A weight of 0 removes the family from the mix.
 */
internal class EventMix(
    /** 1-4 datagrams of valid-garbage hex landing 0/1 ms apart (the stub accepts any bytes — the point is ordering). */
    val datagramBurst: Int = 4,
    /** ENETDOWN-class fault armed on the next `send()` — kills the drain mid-flush. */
    val sendError: Int = 2,
    /** ENETDOWN-class fault surfaced from the parked `receive()`, ordered against queued datagrams. */
    val recvError: Int = 2,
    /** UNAVAILABLE→AVAILABLE availability flap (0-50 ms apart) — mid-handshake/mid-backoff shapes. */
    val availabilityFlap: Int = 2,
    /** `networkId` change (Wi-Fi↔cellular, link-handle churn) — the #222 reconnect-race trigger. */
    val networkChange: Int = 2,
    /** Scripted outcome for the next liveness probe (the #222 seam's scripted queue). */
    val liveness: Int = 1,
    /**
     * Pathological timing: an event scheduled exactly at a keepalive/idle timer deadline ± 1 ms
     * ([SIM_KEEPALIVE_INTERVAL] multiples and [SIM_IDLE_TIMEOUT] from the fixture constants), racing
     * the driver's `select`-loop timer wake against injected input.
     */
    val deadlineProbe: Int = 3,
) {
    internal val total: Int =
        datagramBurst + sendError + recvError + availabilityFlap + networkChange + liveness + deadlineProbe

    init {
        require(total > 0) { "at least one event family must have a non-zero weight" }
    }
}

/** Bounds of one generated fuzz case: virtual horizon plus how many event draws to schedule inside it. */
internal class FuzzConfig(
    val duration: Duration = SIM_IDLE_TIMEOUT + SIM_KEEPALIVE_INTERVAL + 5.seconds,
    val minEvents: Int = 3,
    val maxEvents: Int = 14,
    val mix: EventMix = EventMix(),
)

/**
 * One generated Tier-A fuzz case: the input timeline plus the seed-derived driver configuration it
 * must run under (whether reactive keepalive is armed — with it, the [SIM_IDLE_TIMEOUT] idle timer
 * is starved by design; without it, idle can genuinely fire and close the connection mid-timeline).
 */
internal class FuzzCase(
    val seed: Long,
    val fixture: SimFixture,
    val keepAliveInterval: Duration?,
)

/**
 * The W5 seeded timeline generator (RFC_DETERMINISTIC_SIMULATION.md §6): produces an adversarial
 * [SimFixture] over [FuzzConfig] as a **pure function of [seed]** — same seed, same timeline,
 * on every platform, forever (asserted by `SimFuzzSmokeTests.generator_isPureFunctionOfSeed`).
 * All randomness flows through one `Random(seed)` with a fixed draw order per event family, so a
 * failure's seed is its complete reproduction recipe.
 */
internal class SimTimelineGenerator(
    private val seed: Long,
) {
    fun generate(config: FuzzConfig = FuzzConfig()): FuzzCase {
        val rng = Random(seed)
        // Draw 1: driver config — half the cases run reactive keepalive, half leave idle lethal.
        val keepAlive = if (rng.nextBoolean()) SIM_KEEPALIVE_INTERVAL else null
        // Draw 2: how many event draws (each draw may append several SimEvents, e.g. a burst).
        val draws = rng.nextInt(config.minEvents, config.maxEvents + 1)
        val events = mutableListOf<SimEvent>()
        repeat(draws) {
            when (pickFamily(rng, config.mix)) {
                Family.DATAGRAM_BURST -> events += datagramBurst(rng, randomAt(rng, config))
                Family.SEND_ERROR -> events += SimEvent.SendError(randomAt(rng, config), randomError(rng))
                Family.RECV_ERROR -> events += SimEvent.RecvError(randomAt(rng, config), randomError(rng))
                Family.AVAILABILITY_FLAP -> events += availabilityFlap(rng, randomAt(rng, config))
                Family.NETWORK_CHANGE -> events += SimEvent.Network(randomAt(rng, config), randomNetworkId(rng))
                Family.LIVENESS -> events += SimEvent.Liveness(randomAt(rng, config), randomLiveness(rng))
                Family.DEADLINE_PROBE -> events += deadlineProbe(rng)
            }
        }
        return FuzzCase(
            seed = seed,
            fixture = SimFixture("fuzz-$seed", events.toList(), maxOf(config.duration, events.maxOfOrNull { it.at } ?: Duration.ZERO)),
            keepAliveInterval = keepAlive,
        )
    }

    private enum class Family { DATAGRAM_BURST, SEND_ERROR, RECV_ERROR, AVAILABILITY_FLAP, NETWORK_CHANGE, LIVENESS, DEADLINE_PROBE }

    private fun pickFamily(
        rng: Random,
        mix: EventMix,
    ): Family {
        var roll = rng.nextInt(mix.total)
        for ((family, weight) in listOf(
            Family.DATAGRAM_BURST to mix.datagramBurst,
            Family.SEND_ERROR to mix.sendError,
            Family.RECV_ERROR to mix.recvError,
            Family.AVAILABILITY_FLAP to mix.availabilityFlap,
            Family.NETWORK_CHANGE to mix.networkChange,
            Family.LIVENESS to mix.liveness,
            Family.DEADLINE_PROBE to mix.deadlineProbe,
        )) {
            roll -= weight
            if (roll < 0) return family
        }
        error("unreachable: weights sum to ${mix.total}")
    }

    private fun randomAt(
        rng: Random,
        config: FuzzConfig,
    ): Duration = rng.nextLong(0, config.duration.inWholeMilliseconds + 1).milliseconds

    /** 1-4 datagrams of 1-40 random bytes, landing 0/1 ms apart — ordering pressure, not protocol validity. */
    private fun datagramBurst(
        rng: Random,
        at: Duration,
    ): List<SimEvent> =
        List(rng.nextInt(1, 5)) { i ->
            SimEvent.DatagramIn(at + (i * rng.nextInt(0, 2)).milliseconds, randomHex(rng))
        }

    private fun randomHex(rng: Random): String {
        val len = rng.nextInt(1, 41)
        return buildString(len * 2) {
            repeat(len) {
                val b = rng.nextInt(256)
                append(HEX_DIGITS[b ushr 4])
                append(HEX_DIGITS[b and 0xF])
            }
        }
    }

    private fun randomError(rng: Random): SimError = SimError(ERRNO_CLASS_MESSAGES[rng.nextInt(ERRNO_CLASS_MESSAGES.size)])

    /** UNAVAILABLE at t, back to AVAILABLE 0-50 ms later — the flap lands whole even at the horizon edge. */
    private fun availabilityFlap(
        rng: Random,
        at: Duration,
    ): List<SimEvent> =
        listOf(
            SimEvent.Availability(at, NetworkAvailability.UNAVAILABLE),
            SimEvent.Availability(at + rng.nextLong(0, 51).milliseconds, NetworkAvailability.AVAILABLE),
        )

    private fun randomNetworkId(rng: Random): NetworkId = NETWORK_IDS[rng.nextInt(NETWORK_IDS.size)]

    private fun randomLiveness(rng: Random): Liveness.Result = LIVENESS_RESULTS[rng.nextInt(LIVENESS_RESULTS.size)]

    /** An event scheduled exactly at a timer deadline ± 1 ms (keepalive multiples + the idle deadline). */
    private fun deadlineProbe(rng: Random): List<SimEvent> {
        val base = DEADLINES[rng.nextInt(DEADLINES.size)]
        val at = (base + (rng.nextInt(3) - 1).milliseconds).coerceAtLeast(Duration.ZERO)
        return when (rng.nextInt(3)) {
            0 -> listOf(SimEvent.DatagramIn(at, randomHex(rng)))
            1 -> listOf(SimEvent.SendError(at, randomError(rng)))
            else -> listOf(SimEvent.Network(at, randomNetworkId(rng)))
        }
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"

        /** ENETDOWN-class fault labels — the driver reacts type-agnostically; the label survives into the trace. */
        val ERRNO_CLASS_MESSAGES =
            listOf(
                "ENETDOWN: network is down",
                "ENETUNREACH: network is unreachable",
                "ECONNREFUSED: port unreachable",
                "EBADF: channel closed mid-drain",
            )

        val NETWORK_IDS: List<NetworkId> =
            listOf(
                NetworkId.Unidentified,
                NetworkId.KindOnly(NetworkKind.Wifi),
                NetworkId.KindOnly(NetworkKind.Cellular),
                NetworkId.Link(NetworkKind.Wifi, 1L),
                NetworkId.Link(NetworkKind.Wifi, 2L),
                NetworkId.Link(NetworkKind.Cellular, 3L),
                NetworkId.Link(NetworkKind.Ethernet, 4L),
            )

        val LIVENESS_RESULTS: List<Liveness.Result> =
            listOf(Liveness.Result.Alive, Liveness.Result.Dead, Liveness.Result.Unknown)

        /** Timer deadlines of the fixture-constant driver configs: keepalive fires at each interval multiple; idle at 30 s. */
        val DEADLINES: List<Duration> =
            listOf(
                SIM_KEEPALIVE_INTERVAL,
                SIM_KEEPALIVE_INTERVAL * 2,
                SIM_KEEPALIVE_INTERVAL * 3,
                SIM_IDLE_TIMEOUT,
            )
    }
}
