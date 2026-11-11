package com.ditchoom.socket.quic

/**
 * Multiplier on every Lincheck budget in this module, read from the `LINCHECK_SCALE` environment
 * variable (default 1: the budgets as written). Scales both the scenario count and the invocations
 * per scenario, so total work grows with the square of the value.
 */
internal object LincheckBudget {
    val scale: Int = System.getenv("LINCHECK_SCALE")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    init {
        println("[lincheck] LINCHECK_SCALE=$scale")
    }

    fun scaled(base: Int): Int = base * scale
}
