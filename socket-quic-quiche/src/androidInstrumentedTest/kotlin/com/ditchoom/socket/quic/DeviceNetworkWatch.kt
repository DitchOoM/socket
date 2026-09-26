package com.ditchoom.socket.quic

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.time.Duration

/** The device's airplane-mode setting, as `Settings.Global.AIRPLANE_MODE_ON` holds it. */
internal enum class AirplaneMode { On, Off }

/** Whether the kernel has a route from this device to a harness endpoint. */
internal sealed interface RouteToHarness {
    data object Usable : RouteToHarness

    data class Unusable(
        val cause: IOException,
    ) : RouteToHarness
}

/**
 * Waits on the device's own network state: the airplane-mode setting and the route to [harness].
 *
 * A network-toggling test must not end while the network it took down is still coming back: the next
 * test's connect runs the route probe at once and fails with `Network is unreachable`. Lifting airplane
 * mode is not the network being back — on the API-35 emulator Wi-Fi re-associates seconds later, and
 * how many varies run to run. Each wait re-reads its condition whenever the platform reports a change
 * and is bounded, failing with the last reading.
 *
 * The route is judged the way `UdpSocketChannelFactory`'s route probe judges it — a UDP `connect`,
 * which asks the kernel for a route and sends nothing — so "usable" means exactly that the next
 * connection's probe will resolve.
 */
internal class DeviceNetworkWatch(
    context: Context,
    private val harness: HarnessEndpoint,
) : AutoCloseable {
    private val resolver = context.contentResolver
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // Subscribed before any reading, and conflated: a change between a reading and the wait for the
    // next one is held, never lost.
    private val changes = Channel<Unit>(Channel.CONFLATED)

    private val airplaneObserver =
        object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                changes.trySend(Unit)
            }
        }

    private val defaultNetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                changes.trySend(Unit)
            }

            override fun onLost(network: Network) {
                changes.trySend(Unit)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                changes.trySend(Unit)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                changes.trySend(Unit)
            }
        }

    init {
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, airplaneObserver)
        connectivity.registerDefaultNetworkCallback(defaultNetworkCallback)
    }

    fun airplaneMode(): AirplaneMode =
        if (Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 0) AirplaneMode.Off else AirplaneMode.On

    fun routeToHarness(): RouteToHarness =
        try {
            DatagramChannel.open().use { it.connect(InetSocketAddress(harness.host, harness.port)) }
            RouteToHarness.Usable
        } catch (e: IOException) {
            RouteToHarness.Unusable(e)
        }

    suspend fun awaitAirplaneMode(
        wanted: AirplaneMode,
        bound: Duration,
    ) {
        awaitReading(bound, "airplane mode $wanted", ::airplaneMode) { it == wanted }
    }

    suspend fun awaitRouteToHarness(bound: Duration) {
        awaitReading(bound, "a route to $harness", ::routeToHarness) { it is RouteToHarness.Usable }
    }

    private suspend fun <T> awaitReading(
        bound: Duration,
        wanted: String,
        read: () -> T,
        satisfied: (T) -> Boolean,
    ) {
        var last = read()
        val reached =
            withTimeoutOrNull(bound) {
                while (!satisfied(last)) {
                    changes.receive()
                    last = read()
                }
            }
        if (reached == null) throw AssertionError("the device did not reach $wanted within $bound; last reading: $last")
    }

    override fun close() {
        connectivity.unregisterNetworkCallback(defaultNetworkCallback)
        resolver.unregisterContentObserver(airplaneObserver)
        changes.close()
    }
}
