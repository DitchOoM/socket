package com.ditchoom.socket.quic

import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The ABI regression guard for `quiche_conn_peer_transport_params` — **DitchOoM/socket#388**
 * (https://github.com/DitchOoM/socket/issues/388).
 *
 * ## What went wrong, and why nothing caught it
 * quiche declares `pub struct TransportParams` in `quiche/src/ffi.rs` **without `#[repr(C)]`**, while
 * its immediate neighbours `Stats` and `PathStats` both have it — in 0.28.0, 0.29.2 and 0.29.3 alike.
 * Without the attribute rustc reorders the record by alignment and sinks the 1-byte
 * `disable_active_migration` past the two fields declared after it, so what `quiche/include/quiche.h`
 * calls offset 80 is really `active_conn_id_limit`:
 *
 * | offset | quiche.h claims                  | rustc actually emits             |
 * |--------|----------------------------------|----------------------------------|
 * | 0..72  | the ten `uint64_t`               | correct                          |
 * | 80     | `peer_disable_active_migration`  | `peer_active_conn_id_limit`      |
 * | 88     | `peer_active_conn_id_limit`      | `peer_max_datagram_frame_size`   |
 * | 96     | `peer_max_datagram_frame_size`   | `peer_disable_active_migration`  |
 *
 * `sizeof` is 104 either way. **A size assertion could not have caught this**, which is exactly why
 * this suite asserts the *neighbours* of the flag against values the connection configured: under the
 * broken layout `activeConnIdLimit` reads back as -1 and `maxDatagramFrameSize` as 0, and both
 * assertions below go red on the first run.
 *
 * ## Why the guard is mandatory rather than nice-to-have
 * The value at stake is a **silent kill switch**. A wrong read makes
 * [PeerMigrationPermission.Forbidden] the answer on essentially every connection, so
 * [QuicScope.migrate] returns [MigrationResult.Unmoved.Impossible.PeerForbids], the automatic
 * migration reactor treats that as "and never will" and cancels itself, and active migration simply
 * **vanishes with no error anywhere** — no exception, no log line, no failing handshake. Nothing else
 * in the stack notices, because **quiche does not enforce the peer's `disable_active_migration`
 * itself**: `Connection::migrate()` and `probe_path()` never consult it, and the only uses of it in the
 * library are setting our own local parameter and encoding/decoding it on the wire. Our short-circuit
 * is the entire implementation of RFC 9000 §9's "MUST NOT initiate migration if the peer sent it", so
 * a wrong read is neither corrected nor contradicted by anything downstream.
 *
 * The build works around it with `patchQuicheTransportParamsRepr` (`build.gradle.kts`). **If a quiche
 * bump fixes this upstream, delete that patch and keep this suite** — the patch is the workaround, this
 * is the proof, and this is what will catch the next regression. See #388.
 *
 * ## Why it is a suite with per-platform members
 * The read is per-backend hand-written code — FFM computes byte offsets, JNI fills a `long[]`, the two
 * cinterop backends use a generated struct — so the layout can be wrong on one backend and right on
 * another. It was: the JVM run that passed was the JNI backend, where the accessor was simply unbound.
 * All four must run it: JVM default (JNI), JVM with `-PquicheJvmBackend=ffm` (FFM), Apple cinterop,
 * Linux cinterop.
 */
abstract class PeerTransportParamsLayoutTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Same hook the other suites use: the JVM member turns a missing native lib into a typed skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /**
     * Deliberately **not** the defaults for the two neighbour fields. `idleTimeout` is 10s rather than
     * the 30s default so the assertion cannot pass against a zeroed or stale struct that happens to
     * carry a default, and `activeConnectionIdLimit` is 7 rather than the default 4 so the field is a
     * value nothing else in the process would produce by accident.
     *
     * Datagrams stay off (`datagrams == null`), which is what makes `max_datagram_frame_size` **-1** —
     * "the peer sent no such parameter". That -1 is the single most useful probe in the struct: it is a
     * value no adjacent field ever holds, so reading it at the right offset is strong evidence the whole
     * tail is aligned.
     */
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            activeConnectionIdLimit = 7,
        )

    @Test
    fun peerTransportParamsAreReadAtTheOffsetsTheHeaderDeclares() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    val serverJob = launch { connections { acceptStream() } }
                    try {
                        withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                            val driver = (this as QuicheBackedConnection).quicheDriver
                            val params = driver.peerTransportParams()
                            val negotiated =
                                assertIs<PeerTransportParams.Negotiated>(
                                    params,
                                    "the handshake completed, so the peer's transport parameters must have been " +
                                        "processed; NotYetNegotiated here means the accessor is not reading them at all",
                                )

                            // Head of the struct: the ten uint64s. These are correct under BOTH layouts,
                            // so they prove we are reading the right struct — they cannot catch #388.
                            assertEquals(
                                testQuicOptions.idleTimeout.inWholeMilliseconds,
                                negotiated.maxIdleTimeoutMillis,
                                "peer max_idle_timeout (offset 0)",
                            )
                            assertEquals(
                                testQuicOptions.flowControl.initialMaxData,
                                negotiated.initialMaxData,
                                "peer initial_max_data (offset 16)",
                            )

                            // The neighbours of the bool. THESE are the #388 guard: under the broken
                            // repr(Rust) layout the first reads -1 and the second reads 0.
                            assertEquals(
                                testQuicOptions.activeConnectionIdLimit,
                                negotiated.activeConnIdLimit,
                                "peer active_conn_id_limit (offset 88) — a mismatch here means quiche's " +
                                    "TransportParams lost its #[repr(C)] and the struct is being read at the " +
                                    "wrong offsets. See DitchOoM/socket#388.",
                            )
                            assertEquals(
                                -1L,
                                negotiated.maxDatagramFrameSize,
                                "peer max_datagram_frame_size (offset 96) must be -1 with datagrams disabled — " +
                                    "a 0 here is the signature of the shifted repr(Rust) layout. " +
                                    "See DitchOoM/socket#388.",
                            )

                            // …and the flag itself, plus the decision it drives. The peer is one of our own
                            // servers under the default (permitting) MigrationPolicy, so anything but
                            // Permitted here means active migration has been silently switched off.
                            assertEquals(
                                false,
                                negotiated.disableActiveMigration,
                                "peer disable_active_migration (offset 80)",
                            )
                            assertEquals(
                                PeerMigrationPermission.Permitted,
                                negotiated.migrationPermission,
                                "a loopback peer under the default MigrationPolicy must permit migration; " +
                                    "Forbidden here is the silent kill switch of DitchOoM/socket#388",
                            )
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }
}
