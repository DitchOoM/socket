package com.ditchoom.socket.quic

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import org.junit.AssumptionViolatedException
import kotlin.reflect.KClass

/**
 * Where the device can actually reach one host-side harness server. Supplied by whoever started it.
 *
 * A host *and* a port, because they are one fact: an address is only reachable from the machine it
 * was computed for. The predecessor of this type carried the port and hardcoded the host, which
 * meant one string had to answer three independent questions at once — which transport, which kind
 * of device, and whether anything is listening — and answered all three wrong on real hardware. See
 * [HarnessEndpoints] for how each of the three is now answered separately.
 */
internal data class HarnessEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "harness endpoint host must not be blank" }
        require(port in 1..65535) { "harness endpoint port out of range: $port" }
    }

    override fun toString(): String = "$host:$port"
}

/**
 * What kind of Android target this instrumentation is running on.
 *
 * Load-bearing, not descriptive: an emulator has a built-in `10.0.2.2` alias to the host's loopback
 * and a physical device has nothing of the kind, so the same host-side server has two different
 * addresses depending on which one is executing the test.
 */
internal enum class DeviceKind(
    val label: String,
) {
    Emulator("emulator"),
    PhysicalDevice("physical-device"),
    ;

    companion object {
        fun parse(raw: String?): DeviceKind? = entries.firstOrNull { it.label == raw }

        /**
         * Self-identification, used when the host did not say. Device *kind* is a property of the
         * device, so the device is the authority on it — unlike the harness address, which is a
         * property of the host's network and can only be computed there.
         *
         * `Build.HARDWARE` is the qemu machine type (`goldfish` pre-API-26, `ranchu` after,
         * `cutf_cvm`/`gce_x86` for Cuttlefish) — measured `ranchu` on the local API-36 arm64 AVD.
         * The fingerprint check catches AOSP images whose hardware string is something else.
         *
         * This is the fallback, not the primary: the host's `getprop ro.kernel.qemu` /
         * `ro.boot.qemu` reading (measured `1`/`1` on that AVD, `0`/absent on an SM-F956U1) is
         * passed down as `deviceKind` whenever a task started the harness, and wins.
         */
        fun detect(): DeviceKind =
            if (Build.HARDWARE in EMULATOR_HARDWARE ||
                Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown")
            ) {
                Emulator
            } else {
                PhysicalDevice
            }

        private val EMULATOR_HARDWARE = setOf("goldfish", "ranchu", "cutf_cvm", "gce_x86")
    }
}

/** Where an endpoint's address came from, so a failure can say whether it was told to us or assumed. */
internal enum class EndpointOrigin(
    val label: String,
) {
    /** Passed down as instrumentation arguments by the host-side task that started the server. */
    Supplied("supplied"),

    /**
     * The docker-compose published contract. The one place a constant is right: `docker-compose.yml`
     * publishes a fixed port on the host's loopback, and the emulator's built-in alias makes that
     * loopback addressable. Both halves of that sentence are emulator-specific, which is why this
     * origin never applies to a physical device.
     */
    DockerContract("docker-contract"),
}

/**
 * What the host measured about this device's UDP path to the harness address, before the run.
 *
 * A separate fact from "is an address supplied" and from "is the harness alive", deliberately: those
 * three were one hardcoded string, and collapsing any two of them back together reintroduces the
 * defect. The probe round-trips a datagram on an *ephemeral* socket, so it says nothing about
 * whether the harness port is open — that stays the harness's own question, and a dead harness port
 * on a lane that promised to start one must still fail loudly.
 */
internal enum class HostProbeResult(
    val label: String,
) {
    /** A datagram went device → host → device on the supplied address. */
    RoundTripped("round-tripped"),

    /** The host tried and the datagram did not come back. */
    NoRoute("no-route"),

    /** Nobody probed — e.g. the docker lane, which starts the server before any device exists. */
    NotProbed("not-probed"),
    ;

    companion object {
        fun parse(raw: String?): HostProbeResult = entries.firstOrNull { it.label == raw } ?: NotProbed
    }
}

/**
 * How this device's view of one host-side harness server resolved.
 *
 * Two cases, because "we have an address to try" and "no address exists that this device could try"
 * demand different code and different *skip gates*, and the predecessor's single nullable-free
 * constant could not tell them apart.
 */
internal sealed interface HarnessEndpointResolution {
    /** Name of the harness server, as it appears in skip details. */
    val server: String

    /** An address the device can try. */
    data class Address(
        override val server: String,
        val endpoint: HarnessEndpoint,
        val origin: EndpointOrigin,
        val deviceKind: DeviceKind,
    ) : HarnessEndpointResolution

    /**
     * No address exists that this device could try. [gate] is part of the value because the two
     * causes need opposite answers: a lane that promised to start the harness and did not is a
     * broken lane ([SkipGate.LaneMustRunEveryTest]), while a host with no UDP route to a physical
     * device is a host limitation no lane setting can fix ([SkipGate.HostCannotProvideIt]).
     */
    data class NoAddress(
        override val server: String,
        val deviceKind: DeviceKind,
        val why: String,
        val gate: SkipGate,
    ) : HarnessEndpointResolution
}

/**
 * Endpoints of the host-side harness servers, as told to us by whoever started them.
 *
 * The servers bind port 0 and the OS assigns — so the number cannot be known ahead of time by either
 * side, and cannot be a constant here. `androidQuicIntegrationTest` reads the bound port off each
 * server's `READY port=<n>` line, works out which host address *this* device can reach it on, and
 * passes both down as instrumentation arguments; this is where the device picks them up.
 *
 * ## Why not just pin a port
 * A fixed port is a machine-wide singleton. A server that outlives its run holds it, and the next run
 * fails with "address in use" — a failure mode that exists only because a constant was chosen. It also
 * forbids two suites running at once. The shared test suites already bind `withQuicServer(port = 0, …)`
 * for the same reason; this brings the Android harness in line.
 *
 * ## Why not just pin a host either
 * `10.0.2.2` is the *emulator's* alias for the host's loopback. A physical device has no such alias,
 * so the same constant that is exactly right on an AVD is unroutable on real hardware — and because
 * `adb reverse` forwards **TCP only**, there is no port-forwarding fallback for a UDP harness the way
 * there is for the TCP control channel. The host is therefore computed per device and carried, with
 * one exception below.
 *
 * ## Fallbacks are the docker contract, not a guess
 * When an argument is absent the caller is the **docker** harness path (`test-harness/docker-compose.yml`),
 * where the published port genuinely *is* a fixed contract written in the compose file — that is the one
 * place a constant is correct. So the fallbacks match `harness.env`, and a missing argument means
 * "docker harness", never "we forgot". That contract is emulator-only, for the reason above: it names a
 * port published on the host's loopback, and only an emulator can address that loopback. On a physical
 * device the same missing argument means no address exists at all, which is a typed skip rather than a
 * guess.
 */
internal object HarnessEndpoints {
    /** Published `quic-echo` port in `test-harness/docker-compose.yml`; used when no argument was passed. */
    private const val DOCKER_QUIC_ECHO_PORT = 14433

    /** The emulator's built-in alias for the host's loopback. Exists on an AVD and nowhere else. */
    private const val EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

    /** What the host said this device is, falling back to what the device can see about itself. */
    val deviceKind: DeviceKind
        get() = DeviceKind.parse(argument("deviceKind")) ?: DeviceKind.detect()

    /** UDP QUIC echo server. No `adb reverse` can carry it — see the class KDoc. */
    val quicEcho: HarnessEndpointResolution
        get() {
            val kind = deviceKind
            val supplied =
                suppliedEndpoint(
                    server = QUIC_ECHO,
                    hostArg = "quicEchoHost",
                    portArg = "quicEchoPort",
                    kind = kind,
                )
            if (supplied != null) {
                if (supplied is HarnessEndpointResolution.Address &&
                    HostProbeResult.parse(argument("quicEchoProbe")) == HostProbeResult.NoRoute
                ) {
                    // Measured, not assumed: the host bound an ephemeral UDP socket and asked this
                    // device to round-trip a datagram to `endpoint.host`, and it did not come back.
                    // On a physical device that is a property of the two machines' networks that no
                    // lane setting can change, so it is exempt from the lane gate — but it is still
                    // recorded, and it still names the address that was tried.
                    return HarnessEndpointResolution.NoAddress(
                        server = QUIC_ECHO,
                        deviceKind = kind,
                        why =
                            "the host probed ${supplied.endpoint.host} (harness at ${supplied.endpoint}) from " +
                                "this device before the run and a UDP datagram did not round-trip on an " +
                                "ephemeral port, so there is no path for QUIC either",
                        gate =
                            when (kind) {
                                // An AVD's 10.0.2.2 alias is emulator infrastructure the lane owns; if
                                // that fails, the lane is broken rather than the host being incapable.
                                DeviceKind.Emulator -> SkipGate.LaneMustRunEveryTest
                                DeviceKind.PhysicalDevice ->
                                    SkipGate.HostCannotProvideIt("host-udp-reachable-from-device")
                            },
                    )
                }
                return supplied
            }
            return when (kind) {
                DeviceKind.Emulator ->
                    HarnessEndpointResolution.Address(
                        server = QUIC_ECHO,
                        endpoint = HarnessEndpoint(EMULATOR_HOST_LOOPBACK_ALIAS, DOCKER_QUIC_ECHO_PORT),
                        origin = EndpointOrigin.DockerContract,
                        deviceKind = kind,
                    )
                DeviceKind.PhysicalDevice ->
                    HarnessEndpointResolution.NoAddress(
                        server = QUIC_ECHO,
                        deviceKind = kind,
                        why =
                            "no quicEchoHost/quicEchoPort instrumentation arguments were supplied. The " +
                                "docker fallback ($EMULATOR_HOST_LOOPBACK_ALIAS:$DOCKER_QUIC_ECHO_PORT) is an " +
                                "emulator-only alias for the host's loopback, and adb reverse forwards TCP " +
                                "only, so a physical device has no derivable address for a UDP harness. Run " +
                                "`:socket-quic-quiche:androidQuicIntegrationTest`, which computes a host " +
                                "address this device can reach and carries it down",
                        gate = SkipGate.HostCannotProvideIt("host-udp-reachable-from-device"),
                    )
            }
        }

    /**
     * TCP network-control server. `adb reverse tcp:` maps it onto the device's own loopback, which
     * works identically on an emulator and on real hardware — so unlike [quicEcho] this one has a
     * transport-level answer that does not depend on device kind. It still has no compose-file
     * contract (no service publishes it), so an absent argument is a lane that did not start it.
     */
    val netCtrl: HarnessEndpointResolution
        get() {
            val kind = deviceKind
            return suppliedEndpoint(
                server = NET_CTRL,
                hostArg = "netCtrlHost",
                portArg = "netCtrlPort",
                kind = kind,
            ) ?: HarnessEndpointResolution.NoAddress(
                server = NET_CTRL,
                deviceKind = kind,
                why =
                    "no netCtrlHost/netCtrlPort instrumentation arguments were supplied. The control " +
                        "server binds an OS-assigned port and adb-reverses it, and no compose service " +
                        "publishes a fixed one, so there is nothing to fall back to. Start it with " +
                        "`:socket-quic-quiche:startNetworkControlServer` and pass the port it reports " +
                        "(`androidQuicIntegrationTest` and scripts/android-emulator-tests.sh both do)",
                gate = SkipGate.LaneMustRunEveryTest,
            )
        }

    private const val QUIC_ECHO = "quic-echo"
    private const val NET_CTRL = "net-ctrl"

    /**
     * Both halves or neither. A half-supplied pair is the original defect in miniature — a carried
     * port beside an assumed host — so it is refused rather than completed with a constant.
     */
    private fun suppliedEndpoint(
        server: String,
        hostArg: String,
        portArg: String,
        kind: DeviceKind,
    ): HarnessEndpointResolution? {
        val host = argument(hostArg)
        val rawPort = argument(portArg)
        if (host == null && rawPort == null) return null
        val port = rawPort?.toIntOrNull()
        if (host == null || port == null) {
            return HarnessEndpointResolution.NoAddress(
                server = server,
                deviceKind = kind,
                why =
                    "instrumentation arguments $hostArg/$portArg must be supplied together and were not " +
                        "($hostArg=${host ?: "<absent>"}, $portArg=${rawPort ?: "<absent>"}). A carried port " +
                        "beside an assumed host is what made this harness unroutable on real hardware",
                gate = SkipGate.LaneMustRunEveryTest,
            )
        }
        return HarnessEndpointResolution.Address(
            server = server,
            endpoint = HarnessEndpoint(host, port),
            origin = EndpointOrigin.Supplied,
            deviceKind = kind,
        )
    }

    private fun argument(name: String): String? =
        runCatching { InstrumentationRegistry.getArguments().getString(name) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
}

/**
 * The address to use, or a recorded, typed, counted skip.
 *
 * Replaces `try { connect() } catch (_: Throwable) { assumeTrue(…, false) }`. That shape reported a
 * test that never ran as a test that passed, and — worse — reported *both* "the lane forgot to start
 * the harness" and "this host cannot route to this device" as the same invisible green tick. Here the
 * cause is a [SkipReason] the CI inventory counts and the [SkipGate] decides whether it is also a
 * failure, so `SOCKET_REQUIRE_ALL_TESTS=1` turns the first into red and leaves the second alone.
 */
internal fun HarnessEndpointResolution.addressOrSkip(site: KClass<*>): HarnessEndpointResolution.Address =
    when (this) {
        is HarnessEndpointResolution.Address -> this
        is HarnessEndpointResolution.NoAddress ->
            skipHarness(
                site = site,
                detail = "$server unreachable, device-kind=${deviceKind.label}: $why",
                gate = gate,
            )
    }

/**
 * The harness had an address and did not answer on it.
 *
 * Always [SkipGate.LaneMustRunEveryTest]: an address only exists here because something claimed to
 * have started a server there, so nothing answering is a provisioning failure on a lane that
 * promised to provision — the exact case `SOCKET_REQUIRE_ALL_TESTS=1` exists to make red.
 */
internal fun HarnessEndpointResolution.Address.skipUnanswered(
    site: KClass<*>,
    cause: Throwable?,
): Nothing =
    skipHarness(
        site = site,
        detail =
            "$server did not answer at $endpoint (address ${origin.label}), device-kind=${deviceKind.label}" +
                (cause?.let { ": ${it::class.simpleName}: ${it.message}" } ?: ""),
        gate = SkipGate.LaneMustRunEveryTest,
    )

/**
 * Record the skip, then raise the assumption that makes the runner *count* it.
 *
 * Two steps because they answer different questions. [recordSkip] emits the greppable marker the CI
 * skip inventory reads and throws when the lane forbids skipping. The [AssumptionViolatedException]
 * is what puts a `<skipped>` element in the instrumented result XML — and it carries the same detail,
 * so the address that was tried is legible from the XML alone, without needing the process's stdout
 * to have been captured.
 */
private fun skipHarness(
    site: KClass<*>,
    detail: String,
    gate: SkipGate,
): Nothing {
    recordSkip(site, SkipReason.HarnessUnreachableFromDevice(detail), gate)
    throw AssumptionViolatedException(detail)
}
