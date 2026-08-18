package com.ditchoom.socket.quic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **A declaration is a claim. This suite is what makes a false one fail.**
 *
 * Phase 3/4 of this campaign replaced two "silently disabled" defaults with sealed declarations that a
 * platform has to state out loud: [MigrationCapability] (can this connection move its own path, and if
 * not, *why* not) and [LocalEndpointSupport] (can this factory bind the endpoint a caller names). Both
 * types did their job — a new platform cannot compile without answering — but nothing anywhere checked
 * that the answer is **true**. A platform can still state [MigrationCapability.Supported] over a factory
 * that opens nothing, or [LocalEndpointSupport.Bindable] over a `connect` that discards the endpoint it
 * was handed, and every existing test stays green: the driver's own logic is a faithful *translation* of
 * the declaration (pinned by `MigrationCapabilityAnswerTests`), so a lie propagates consistently instead
 * of contradicting itself.
 *
 * That is the same failure shape the declarations were introduced to end, one level up. Apple did not
 * ship without migration because it answered wrongly; it shipped that way because nothing asked. Making
 * the question mandatory only moves the silence from "no answer" to "an unverified answer" unless
 * something measures the platform and compares.
 *
 * ## What each test compares, and what it therefore catches
 * Every test reads the **declaration off a live connection** — `QuicheBackedConnection.quicheDriver`
 * then [QuicheDriver.migration], the very value that connection was constructed with — and then makes
 * the platform demonstrate it. Nothing here restates a claim in test code; a test that hard-codes
 * "Apple is PlatformAssigned" would just be a second copy of the declaration, green by construction and
 * wrong in the same direction.
 *
 * - [aPermittingClientDeclaresItCanMigrateAndMigrateAgrees] — a client under a permitting
 *   [MigrationPolicy] must declare [MigrationCapability.Supported]. Flip any platform's declaration to
 *   [MigrationCapability.BackendCannotMigrate] (which is exactly what "shipped with no path factory"
 *   looks like) and this goes red, on that platform only.
 * - [aForbiddenPolicyDeclaresItAndBuildsNoWiring] — the one "cannot" case a caller can request on every
 *   platform, so both branches of the type are exercised everywhere. Catches a platform that builds a
 *   path factory anyway: advertising `disable_active_migration` while retaining the ability to move is
 *   the lie [MigrationPolicy.Forbidden]'s own docs call out.
 * - [aServerAcceptedConnectionDeclaresTheRoleConstraint] — RFC 9000 §9 is client-only, so the server's
 *   drivers must say [MigrationCapability.ServerConnection] rather than carrying live wiring that the
 *   connection wrapper then hides by short-circuiting `migrate()`.
 * - [localEndpointSupportIsWhatTheFactoryActuallyBinds] — the sharpest one, because it is the only
 *   assertion here that the declaration cannot also satisfy by controlling the code under test: it calls
 *   [UdpChannelFactory.openPath] with an explicit port and looks at the port that came back. Flip
 *   Bindable↔PlatformAssigned in either direction and it goes red, because the socket's real behaviour
 *   does not move with the label.
 * - [anUnbindableLocalEndpointIsRefusedRatherThanSubstituted] — the caller-visible half: the driver must
 *   turn a [LocalEndpointSupport.PlatformAssigned] declaration into
 *   [MigrationResult.Unmoved.Failed.EndpointNotSelectable], and a [LocalEndpointSupport.Bindable] one
 *   into a migration that lands on the endpoint that was asked for — never a `Succeeded` naming
 *   somewhere else.
 *
 * ## Why it lives in `src/sharedQuicheTestSuites/kotlin`
 * Same reason as [PeerTransportParamsLayoutTestSuite]: it needs this module's `internal` seams
 * ([QuicheBackedConnection], [QuicheDriver.migration]), so :socket-testsuite — which only sees the
 * public `:socket-quic-default` facade — cannot host it; and `androidInstrumentedTest` deliberately does
 * not `dependsOn(commonTest)`, so a `commonTest` home would exclude the JNI backend on real Android.
 * This srcDir is compiled by both.
 */
abstract class QuicCapabilityConformanceTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Same hook as every other suite: the JVM/Android members turn a missing native into a typed skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /**
     * [MigrationPolicy.Manual] rather than the default [MigrationPolicy.Automatic] on purpose: the two
     * produce the *same* capability (`clientMigrationCapability` maps them identically, which
     * `MigrationCapabilityAnswerTests` pins), and Manual leaves no auto-migration reactor running that
     * could issue a second `migrate()` concurrently with the explicit ones below. Same capability, one
     * caller.
     */
    private val permitting =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            migration = MigrationPolicy.Manual,
        )

    private val forbidding = permitting.copy(migration = MigrationPolicy.Forbidden)

    // ---- migration capability ↔ what migrate() answers ---------------------------------------------

    @Test
    fun aPermittingClientDeclaresItCanMigrateAndMigrateAgrees() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withLiveClient(permitting) {
                    val declared = declaredMigration()
                    assertIs<MigrationCapability.Supported>(
                        declared,
                        "a client connection on a real quiche engine under a permitting MigrationPolicy must " +
                            "declare that it can migrate. Anything else here means this platform's connection setup " +
                            "wired no UdpChannelFactory — the exact shape in which Apple shipped without RFC 9000 §9 " +
                            "migration for a year, with no red test because nothing asked. Declared: $declared",
                    )
                    // The behavioural half of the same claim: the Impossible family means "and never will,
                    // whatever the network does", so a connection that just declared Supported cannot answer
                    // one. (Whether the migration then completes is `QuicActiveMigrationTestSuite`'s question,
                    // deliberately not re-asked here.)
                    val result = migrate()
                    assertTrue(
                        result !is MigrationResult.Unmoved.Impossible,
                        "the connection declares $declared, so migrate() must not answer a permanent " +
                            "impossibility. Got: $result",
                    )
                }
            }
        }

    @Test
    fun aForbiddenPolicyDeclaresItAndBuildsNoWiring() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withLiveClient(forbidding) {
                    val declared = declaredMigration()
                    assertEquals(
                        MigrationCapability.PolicyForbids,
                        declared,
                        "MigrationPolicy.Forbidden advertises disable_active_migration on the wire, so this " +
                            "endpoint promised the peer it will not move either. A Supported declaration here means " +
                            "the platform built path-migration wiring anyway — an endpoint that advertises the " +
                            "parameter while retaining the ability to migrate is lying to its peer. Declared: $declared",
                    )
                    assertEquals(
                        MigrationResult.Unmoved.Impossible.PolicyForbids,
                        migrate(),
                        "a forbidden policy must name itself as the reason, not blame the backend or the peer",
                    )
                }
            }
        }

    @Test
    fun aServerAcceptedConnectionDeclaresTheRoleConstraint() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = permitting) {
                    // Ship the server-side facts OUT of the handler and assert in the test body: an
                    // assertion thrown inside a launched handler cancels its sibling silently and leaves the
                    // client's await() hanging to the whole-test budget, masking the cause.
                    val observed = CompletableDeferred<ServerSideFacts>()
                    val serverJob =
                        launch {
                            connections {
                                observed.complete(ServerSideFacts(declaredMigration(), migrate()))
                            }
                        }
                    try {
                        withQuicConnection("127.0.0.1", port, permitting, timeout = 10.seconds) {
                            val facts = withTimeout(10.seconds) { observed.await() }
                            assertEquals(
                                MigrationCapability.ServerConnection,
                                facts.declared,
                                "only clients migrate in QUIC v1 (RFC 9000 §9), so a server-accepted driver must " +
                                    "declare the role constraint. A Supported declaration here would also change " +
                                    "which sockaddr the driver treats as its primary local path, and the connection " +
                                    "wrapper's short-circuiting migrate() would hide it. Declared: ${facts.declared}",
                            )
                            assertEquals(
                                MigrationResult.Unmoved.Impossible.ServerConnection,
                                facts.answered,
                                "a server connection must answer the role constraint, never blame the backend",
                            )
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    // ---- local-endpoint support ↔ what openPath actually binds -------------------------------------

    @Test
    fun localEndpointSupportIsWhatTheFactoryActuallyBinds() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withLiveClient(permitting) {
                    val factory = assertIs<MigrationCapability.Supported>(declaredMigration()).channelFactory
                    val observed = probeExplicitPortBinding(factory)
                    when (factory.localEndpointSupport) {
                        LocalEndpointSupport.Bindable ->
                            assertIs<ExplicitPortBinding.Honoured>(
                                observed,
                                "this factory declares LocalEndpointSupport.Bindable, which the driver reads as " +
                                    "permission to pass a caller-named endpoint straight through — so openPath must " +
                                    "bind the port it was given. Observed: $observed",
                            )
                        LocalEndpointSupport.PlatformAssigned ->
                            assertTrue(
                                observed !is ExplicitPortBinding.Honoured,
                                "this factory declares LocalEndpointSupport.PlatformAssigned, on the strength of " +
                                    "which the driver REFUSES every caller-named endpoint with " +
                                    "EndpointNotSelectable. It just bound one, so the refusal is turning away " +
                                    "requests this platform can serve. Observed: $observed",
                            )
                    }
                }
            }
        }

    @Test
    fun anUnbindableLocalEndpointIsRefusedRatherThanSubstituted() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withLiveClient(permitting) {
                    val factory = assertIs<MigrationCapability.Supported>(declaredMigration()).channelFactory
                    val port =
                        ephemeralPortFrom(factory)
                            ?: throw AssertionError("openPath(null, 0) — the request every platform serves — opened nothing")
                    val result = migrate(MigrationTarget.LocalEndpoint("127.0.0.1", port))
                    when (factory.localEndpointSupport) {
                        LocalEndpointSupport.Bindable -> {
                            assertTrue(
                                result !is MigrationResult.Unmoved.Failed.EndpointNotSelectable,
                                "a Bindable factory must not have its caller-named endpoint refused as unselectable",
                            )
                            // The `Succeeded` value names the endpoint the platform BOUND. On a platform that
                            // honours the request those must be the same endpoint — a success naming somewhere
                            // else is precisely the silent substitution EndpointNotSelectable exists to prevent.
                            if (result is MigrationResult.Succeeded) {
                                assertEquals(
                                    QuicLocalEndpoint("127.0.0.1", port),
                                    result.localEndpoint,
                                    "migration to a named endpoint succeeded somewhere else",
                                )
                            }
                        }
                        LocalEndpointSupport.PlatformAssigned ->
                            assertEquals(
                                MigrationResult.Unmoved.Failed.EndpointNotSelectable,
                                result,
                                "this platform assigns the local endpoint itself, so a named one must be REFUSED. " +
                                    "Any other answer means the request was quietly substituted, which makes the " +
                                    "reported endpoint — the only way a caller learns where it landed — a lie",
                            )
                    }
                }
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------

    /** The server-side facts, read inside the handler and asserted outside it. */
    private class ServerSideFacts(
        val declared: MigrationCapability,
        val answered: MigrationResult,
    )

    /**
     * What a factory did with an explicitly named local port. Sealed rather than a `Boolean` because
     * "refused it" and "bound a different one" are different platform behaviours and the failure message
     * has to say which happened.
     */
    private sealed interface ExplicitPortBinding {
        /** The socket bound exactly the port that was asked for. */
        data class Honoured(
            val port: Int,
        ) : ExplicitPortBinding

        /** The socket opened, but somewhere else — the request was discarded, not rejected. */
        data class Substituted(
            val requested: Int,
            val bound: Int,
        ) : ExplicitPortBinding

        /** The factory rejected the request outright. */
        data class Refused(
            val requested: Int,
            val cause: Throwable,
        ) : ExplicitPortBinding

        /** Not even the default (`null`, 0) request could be served, so nothing was learned. */
        data object NoCandidatePort : ExplicitPortBinding
    }

    /**
     * Open a path at a platform-chosen ephemeral port, note it, and close it — a port this process just
     * held is one the host will accept a bind on, without a hard-coded number that could collide with
     * whatever else is running on the CI box.
     */
    private suspend fun ephemeralPortFrom(factory: UdpChannelFactory): Int? =
        runCatching {
            val path = factory.openPath(null, 0)
            try {
                path.localEndpoint.port
            } finally {
                path.channel.close()
                path.release()
            }
        }.getOrNull()

    /**
     * Ask [factory] for a specific local port and report what it did.
     *
     * [ATTEMPTS] candidates, not one, for a single reason: on a platform that assigns the endpoint
     * itself, the assigned port could coincide with the requested one by chance (~1 in 16k of the
     * ephemeral range), and a single trial would then read as "honoured". Returning [Honoured] on the
     * *first* exact match while requiring all attempts to miss before concluding otherwise makes the
     * false "honoured" as unlikely as three independent coincidences, and costs nothing when the
     * platform really does bind — that case matches on attempt one.
     */
    private suspend fun probeExplicitPortBinding(factory: UdpChannelFactory): ExplicitPortBinding {
        var outcome: ExplicitPortBinding = ExplicitPortBinding.NoCandidatePort
        repeat(ATTEMPTS) {
            val candidate = ephemeralPortFrom(factory) ?: return@repeat
            val path =
                try {
                    factory.openPath("127.0.0.1", candidate)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    outcome = ExplicitPortBinding.Refused(candidate, e)
                    return@repeat
                }
            val bound =
                try {
                    path.localEndpoint.port
                } finally {
                    path.channel.close()
                    path.release()
                }
            if (bound == candidate) return ExplicitPortBinding.Honoured(bound)
            outcome = ExplicitPortBinding.Substituted(candidate, bound)
        }
        return outcome
    }

    /** The declaration this live connection was built with — the claim every test here measures. */
    private fun QuicScope.declaredMigration(): MigrationCapability = (this as QuicheBackedConnection).quicheDriver.migration

    /**
     * A live client connection to an in-process loopback server, with an accepting server handler so the
     * handshake completes. Every test here needs the same three lines and none of them care about
     * streams; the client body runs INLINE for the reason the passive-migration suite documents — a
     * per-op timeout inside a child `launch` cancels it silently and hangs the test on an unbounded
     * `await`.
     */
    private suspend fun withLiveClient(
        options: QuicOptions,
        body: suspend QuicScope.() -> Unit,
    ) = // The server always runs [permitting], whatever the CLIENT policy under test is: a server that
        // advertised disable_active_migration would make the client's migrate() answer PeerForbids, which
        // is a fact about the peer and would mask the fact about this endpoint that each test is asking.
        withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = permitting) {
            coroutineScope {
                val serverJob = launch { connections { acceptStream() } }
                try {
                    withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) { body() }
                } finally {
                    serverJob.cancel()
                }
            }
        }

    private companion object {
        const val ATTEMPTS = 3
    }
}
