package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Migrates a live QUIC stream **off a path that has actually died**, inside a network namespace.
 *
 * Runs under `test-harness/netns/run-netns-quic-migration.sh`, not as a JUnit test: it needs a namespace
 * built around it and it mutates that namespace's interfaces mid-run. Self-skips (exit 0, loud note) when
 * the environment is absent, so a stray invocation on a developer's host is a no-op rather than a failure —
 * the same contract `NetnsRouteResolutionTest` uses.
 *
 * ## Why this exists
 * Every migration test in this repo migrates away from a **healthy** path.
 * `QuicActiveMigrationTestSuite` calls `migrate()` with no target, which its own KDoc describes as "a fresh
 * ephemeral socket on the current default interface" — a local **port** change on loopback, where
 * `127.0.0.1` never dies. Issue #393 says it outright: that test "passes while the property it names does
 * not hold in the field". It took a 124-minute on-device recording to find the defect, because CI could not
 * express it.
 *
 * A namespace can. Three ingredients are needed together, and loopback supplies none:
 *
 * | ingredient | loopback | here |
 * |---|---|---|
 * | the client's local **address** changes | ✗ (port only) | ✓ `eth-a` → `eth-b` |
 * | the old path **dies** | ✗ | ✓ the interface is downed mid-stream |
 * | the stream is **carrying data** across it | ✗ (idle) | ✓ continuous echo |
 *
 * ## What is asserted
 * Not "the connection survived" — that is precisely the assertion that failed to catch #393, where the
 * connection stayed healthy for 101 minutes *while the stream was dead*. The assertion is **byte-exact
 * stream continuity**: everything received must be an exact, in-order prefix of everything sent, across the
 * death of the old path.
 *
 * That invariant, rather than a per-read equality check, is deliberate. A read may return a coalesced or
 * partial chunk, and bytes rescued from a timed-out read are delivered on the *next* read
 * ([QuicheDriver.salvageCancelledRecv]) — both are correct behaviour that a naive
 * `received == sentPayload` check would report as corruption.
 *
 * ## Topology
 * The script builds three dummy interfaces in one rootless namespace: `eth-srv` carries the server address,
 * `eth-a` and `eth-b` the two client addresses. Every address is local, so packets are delivered by the
 * kernel rather than crossing a wire — what matters is that the addresses are **distinct and separately
 * revocable**. Downing `eth-a` removes its address, so the socket bound to it can no longer send: a path
 * that dies for a reason outside the QUIC stack's control, which is the condition being reproduced.
 *
 * ## Honest limits
 * A namespace cannot reproduce what actually *arms* #393 in the field: Android's ConnectivityManager keeping
 * a degrading-but-still-"validated" Wi-Fi as the default network, and the read timeouts that lag generates.
 * Nor the RRC radio promotion measured on device (344–601 ms to cellular vs 39–45 ms back to Wi-Fi). This is
 * the CI regression gate; `DeviceHandoffProbe` remains the field validation. Neither replaces the other.
 */
object NetnsQuicMigrationProbe {
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun note(message: String) = println("[netns-quic] $message")

    @JvmStatic
    fun main(args: Array<String>) {
        // Self-skip: absent env means this was not launched by the namespace driver.
        val serverAddr = env("QUIC_NETNS_SERVER_ADDR")
        val pathA = env("QUIC_NETNS_PATH_A")
        val pathB = env("QUIC_NETNS_PATH_B")
        val ifaceA = env("QUIC_NETNS_IFACE_A")
        if (serverAddr == null || pathA == null || pathB == null || ifaceA == null) {
            note(
                "skipped — needs QUIC_NETNS_SERVER_ADDR / QUIC_NETNS_PATH_A / QUIC_NETNS_PATH_B / " +
                    "QUIC_NETNS_IFACE_A; run via test-harness/netns/run-netns-quic-migration.sh",
            )
            return
        }

        val failures = mutableListOf<String>()
        try {
            runBlocking { runProbe(serverAddr, pathA, pathB, ifaceA, failures) }
        } catch (t: Throwable) {
            failures += "probe threw ${t::class.simpleName}: ${t.message}"
        }

        if (failures.isEmpty()) {
            note("PASS — stream carried byte-exact data across the death of $ifaceA")
        } else {
            failures.forEach { note("FAIL — $it") }
            exitProcess(1)
        }
    }

    private suspend fun runProbe(
        serverAddr: String,
        pathA: String,
        pathB: String,
        ifaceA: String,
        failures: MutableList<String>,
    ) {
        val options =
            QuicOptions(
                alpnProtocols = listOf("test"),
                verifyPeer = false,
                idleTimeout = 30.seconds,
                keepAliveInterval = 5.seconds,
            )

        // coroutineScope so the server's handler job has an owner — withQuicServer's receiver is a
        // QuicServer, not a CoroutineScope, and the scope must outlive the connection block.
        coroutineScope {
            withQuicServer(
                binding = QuicPortBinding.Own(0, serverAddr),
                tlsConfig = netnsTlsConfig(),
                quicOptions = options,
            ) {
                val serverPort = port
                val serverJob = launch { connections { echoEveryStream() } }
                try {
                    withQuicConnection(serverAddr, serverPort, options, timeout = 20.seconds) {
                        val stream = openStream()
                        // Everything sent, and everything received, as flat text. The invariant is that the
                        // second is always an exact in-order prefix of the first.
                        val sent = StringBuilder()
                        val received = StringBuilder()

                        suspend fun exchange(
                            tag: String,
                            rounds: Int,
                        ) {
                            repeat(rounds) { i ->
                                val payload = "$tag-$i;"
                                val out = BufferFactory.Default.allocate(payload.length)
                                out.writeString(payload, Charset.UTF8)
                                out.resetForRead()
                                try {
                                    stream.writeFully(out, 10.seconds)
                                } finally {
                                    out.freeIfNeeded()
                                }
                                sent.append(payload)
                                when (val r = stream.read(10.seconds)) {
                                    is ReadResult.Data -> {
                                        try {
                                            received.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                                        } finally {
                                            // read() transfers ownership; write() takes none (#401).
                                            r.buffer.freeIfNeeded()
                                        }
                                    }
                                    else -> failures += "$tag round $i read returned $r instead of data"
                                }
                            }
                        }

                        fun checkPrefix(stage: String) {
                            if (!sent.startsWith(received)) {
                                val at = received.indices.firstOrNull { it >= sent.length || sent[it] != received[it] } ?: 0
                                failures +=
                                    "$stage: received is no longer an in-order prefix of sent at byte $at — " +
                                    "sent=[${sent.substring(maxOf(0, at - 24), minOf(sent.length, at + 24))}] " +
                                    "recv=[${received.substring(maxOf(0, at - 24), minOf(received.length, at + 24))}]"
                            }
                        }

                        // 1. Move onto path A explicitly. There is no client-side local-bind at connect time —
                        //    MigrationTarget.LocalAddress is the only way to choose the source address.
                        when (val onA = migrate(MigrationTarget.LocalAddress(pathA))) {
                            is MigrationResult.Succeeded ->
                                if (onA.localEndpoint.host != pathA) {
                                    failures += "migrate to A resolved to ${onA.localEndpoint.host}, expected $pathA"
                                }
                            else -> {
                                failures += "could not migrate onto path A ($pathA): $onA"
                                return@withQuicConnection
                            }
                        }
                        exchange("on-a", rounds = 8)
                        checkPrefix("while on path A")

                        // 2. Kill path A. Downing the interface revokes its address, so the socket bound to it
                        //    can no longer send — a path that dies for a reason the QUIC stack does not control.
                        //    This is the ingredient loopback cannot supply.
                        val downed = runCatching { ProcessBuilder("ip", "link", "set", ifaceA, "down").inheritIO().start().waitFor() }
                        if (downed.getOrNull() != 0) {
                            failures += "could not down $ifaceA: ${downed.exceptionOrNull()?.message ?: "exit ${downed.getOrNull()}"}"
                            return@withQuicConnection
                        }
                        note("downed $ifaceA — path A is dead")

                        // 3. Migrate onto path B across that death, then prove the stream still carries data AND
                        //    that nothing sent before the migration was lost.
                        when (val onB = migrate(MigrationTarget.LocalAddress(pathB))) {
                            is MigrationResult.Succeeded ->
                                if (onB.localEndpoint.host != pathB) {
                                    failures += "migrate to B resolved to ${onB.localEndpoint.host}, expected $pathB"
                                }
                            else -> {
                                failures += "migration off the dead path A onto B ($pathB) failed: $onB"
                                return@withQuicConnection
                            }
                        }
                        exchange("on-b", rounds = 8)
                        checkPrefix("after migrating off the dead path")

                        if (received.length != sent.length) {
                            failures +=
                                "stream lost data across the migration: sent ${sent.length}B, received " +
                                "${received.length}B (a prefix that never caught up is #393's shape)"
                        }
                        stream.close()
                    }
                } finally {
                    serverJob.cancel()
                }
            }
        }
    }

    /**
     * Server side: echo every stream, freeing each read buffer once echoed.
     *
     * [writeFully], not a bare `write`: a QUIC stream write returns a possibly-partial count at a
     * flow-control boundary, and a single `write` silently truncates the echo — which would look exactly
     * like the data loss this probe exists to detect.
     */
    private suspend fun QuicScope.echoEveryStream() {
        streams().collect { stream ->
            launch {
                try {
                    while (true) {
                        val data = stream.read(20.seconds)
                        if (data is ReadResult.Data) {
                            try {
                                stream.writeFully(data.buffer, 10.seconds)
                            } finally {
                                data.buffer.freeIfNeeded()
                            }
                        } else {
                            break
                        }
                    }
                } finally {
                    stream.close()
                }
            }
        }
    }

    /** Cert/key the namespace driver stages; paths come from the same env contract. */
    private fun netnsTlsConfig(): QuicTlsConfig =
        QuicTlsConfig(
            certChainPath = env("QUIC_NETNS_CERT") ?: error("QUIC_NETNS_CERT unset"),
            privKeyPath = env("QUIC_NETNS_KEY") ?: error("QUIC_NETNS_KEY unset"),
        )
}
