package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A `Transport` fake that plays a scripted outcome per connect (RFC §10's `ScriptedTransport`), so the
 * whole fallback loop is exercised deterministically with no real network. Successes hand back a live
 * in-memory `ByteStream`; [Outcome.Hang] suspends forever so a per-attempt timeout can fire;
 * [Outcome.SucceedAfter] connects after a virtual-time delay so races can be interleaved exactly.
 * [family] tags the rung's lane for the staggered race; [seenConfigs] and [streams] record what each
 * connect was handed and produced.
 */
internal class ScriptedTransport(
    private val name: String,
    private vararg val outcomes: Outcome,
    override val family: TransportFamily = TransportFamily.Tcp,
) : Transport {
    sealed interface Outcome {
        data object Succeed : Outcome

        data class SucceedAfter(
            val delay: Duration,
        ) : Outcome

        data class Fail(
            val error: Throwable,
        ) : Outcome

        data object Hang : Outcome
    }

    private val script = ArrayDeque(outcomes.toList())
    var connectCount = 0
        private set
    val seenConfigs = ArrayList<TransportConfig>()
    val streams = ArrayList<ByteStream>()

    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        connectCount++
        seenConfigs += config
        return when (val outcome = script.removeFirstOrNull() ?: Outcome.Succeed) {
            Outcome.Succeed -> newStream(config)
            is Outcome.SucceedAfter -> {
                delay(outcome.delay)
                newStream(config)
            }
            is Outcome.Fail -> throw outcome.error
            Outcome.Hang -> awaitCancellation()
        }
    }

    private fun newStream(config: TransportConfig): ByteStream = MemoryTransport.createPair(config).first.also { streams += it }

    override fun toString() = name
}
