package com.ditchoom.socket.testkit.migration

import com.ditchoom.socket.testkit.echo.EchoLivenessVerdict
import com.ditchoom.socket.testkit.echo.RunLiveness

/**
 * The `447-VERDICT` for one connection, with echo liveness in front of the pool verdict: a path
 * that migrated and then answered no echo has not been validated, whatever its probes said.
 */
public sealed interface ConnectionVerdict {
    public val pool: PoolRecoveryVerdict
    public val line: String

    public data class Standing(
        override val pool: PoolRecoveryVerdict,
    ) : ConnectionVerdict {
        override val line: String get() = pool.line
    }

    public data class Silenced(
        override val pool: PoolRecoveryVerdict,
        val silent: EchoLivenessVerdict.Silent,
    ) : ConnectionVerdict {
        override val line: String
            get() =
                "FAIL — no answered echo for ${silent.longestQuiet.length} (limit ${silent.limit}), so the path layer's " +
                    "verdict is void; on its own it read: ${pool.line}"
    }
}

/** The `447-VERDICT run` line, gated the same way by the run's worst connection. */
public sealed interface RunVerdict {
    public val pool: PoolRecoveryVerdict
    public val line: String

    public data class Standing(
        override val pool: PoolRecoveryVerdict,
        val connections: Int,
    ) : RunVerdict {
        override val line: String get() = pool.runLine(connections)
    }

    public data class Silenced(
        override val pool: PoolRecoveryVerdict,
        val silent: RunLiveness.Silent,
    ) : RunVerdict {
        override val line: String
            get() =
                "FAIL — connection ${silent.worstConnection} went ${silent.longestQuiet.length} without an answered echo " +
                    "(limit ${silent.limit}); a path that answers no echo has not been validated, so the path layer's " +
                    "verdict is void; on its own it read: ${pool.runLine(silent.connections)}"
    }
}

public fun PoolRecoveryVerdict.forConnection(liveness: EchoLivenessVerdict): ConnectionVerdict = ConnectionVerdict.Standing(this)

public fun PoolRecoveryVerdict.forRun(
    connections: Int,
    liveness: RunLiveness,
): RunVerdict = RunVerdict.Standing(this, connections)
