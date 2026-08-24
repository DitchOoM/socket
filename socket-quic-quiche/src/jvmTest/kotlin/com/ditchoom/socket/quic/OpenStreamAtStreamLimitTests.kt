package com.ditchoom.socket.quic

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * `openStream()` at the peer's stream limit (#423).
 *
 * ## Why this exists
 *
 * #423 made `openStream()` materialise the stream with quiche instead of only reserving an id, so that
 * a read before the first write is not answered with `INVALID_STREAM_STATE`. The materialising send is
 * also the call that fails when the peer's `initial_max_streams` is reached — and the first version of
 * that fix **discarded its result**.
 *
 * That put the bug straight back at the boundary, which is the interesting part: `openStream()` handed
 * back a slot quiche had refused to create, and the very next read on it answered
 * `INVALID_STREAM_STATE` — the exact answer #423 exists to remove. An adversarial review demonstrated
 * it with this setup, so it is pinned here.
 *
 * The refusal is now reported where it happens, as a typed [QuicStreamOpenException] carrying quiche's
 * own code, and the stream id is handed back rather than burned.
 *
 * ## Why the sim rather than a loopback server
 *
 * `initial_max_streams` is a transport parameter, so reaching it needs a peer configured to advertise a
 * small one. The semantic sim drives real quiche on both ends with a configurable [QuicOptions], which
 * makes "the third stream is one too many" exact rather than something to provoke.
 */
class OpenStreamAtStreamLimitTests {
    @Test
    fun openStreamPastThePeerLimitFailsInsteadOfHandingBackAStreamQuicheRefused() =
        runBlocking {
            skipOnMissingNativeLib(OpenStreamAtStreamLimitTests::class) {
                val options =
                    semanticSimOptions(idleTimeout = 10.seconds).let {
                        it.copy(flowControl = it.flowControl.copy(initialMaxStreamsBidi = STREAM_LIMIT))
                    }
                withSemanticSim(ImpairmentConfig(seed = 423L), quicOptions = options) {
                    // Up to the limit, opening works.
                    repeat(STREAM_LIMIT.toInt()) { client.openStream() }

                    // One past it must FAIL here, not hand back a stream quiche never created.
                    val failure =
                        assertFailsWith<QuicStreamOpenException> {
                            client.openStream()
                        }

                    assertEquals(
                        QuicheDriver.QUICHE_ERR_STREAM_LIMIT,
                        failure.quicheErrorCode,
                        "openStream past the peer's initial_max_streams must report quiche's own " +
                            "STREAM_LIMIT (#423). Discarding it is what made openStream() hand back a " +
                            "slot quiche had refused, whose next read then answered " +
                            "INVALID_STREAM_STATE — the very answer #423 removed.",
                    )
                }
            }
        }

    private companion object {
        /** Small enough that "one too many" is exact; large enough that the happy path is exercised. */
        const val STREAM_LIMIT = 2L
    }
}
