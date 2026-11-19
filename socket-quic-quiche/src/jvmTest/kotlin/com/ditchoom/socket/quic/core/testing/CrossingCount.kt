package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.QuicheApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Counts the calls the core makes into [QuicheApi], by function, on every thread: each is one crossing into native
 * code on JNI, FFM and cinterop alike. Counting is off until [start]; [stop] freezes it.
 */
internal class CrossingCount(
    real: QuicheApi,
) {
    private val counts = ConcurrentHashMap<String, LongAdder>()

    @Volatile
    private var counting = false

    /**
     * The counting api. It is its own identity: `equals` and `hashCode` answer for the proxy, never for [real], because a
     * key that equals [real] one way and not the other breaks every map the core keys by api (the setup lane's shared
     * configs freed one still in use).
     */
    val api: QuicheApi =
        Proxy.newProxyInstance(QuicheApi::class.java.classLoader, arrayOf(QuicheApi::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> if (method.parameterCount == 1) return@newProxyInstance proxy === args[0]
                "hashCode" -> if (method.parameterCount == 0) return@newProxyInstance System.identityHashCode(proxy)
                "toString" -> if (method.parameterCount == 0) return@newProxyInstance "CrossingCount($real)"
            }
            if (counting) counts.computeIfAbsent(method.name.substringBefore('-')) { LongAdder() }.increment()
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (thrown: InvocationTargetException) {
                throw thrown.targetException
            }
        } as QuicheApi

    fun start() {
        counts.clear()
        counting = true
    }

    fun stop() {
        counting = false
    }

    /** The calls counted per function, most first. */
    fun byFunction(): List<Pair<String, Long>> = counts.map { (name, n) -> name to n.sum() }.sortedByDescending { it.second }

    fun total(): Long = counts.values.sumOf { it.sum() }

    /** The calls to the functions in [names]. */
    fun of(names: Set<String>): Long = counts.filterKeys { it in names }.values.sumOf { it.sum() }

    /** The breakdown per [per] operations, as a table. */
    fun report(per: Int): String =
        byFunction().joinToString("\n", prefix = "  %6.2f total\n".format(total().toDouble() / per)) { (name, n) ->
            "  %6.2f %s".format(n.toDouble() / per, name)
        }
}
