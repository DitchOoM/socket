package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.net.NetworkCapabilities as AndroidNetworkCapabilities

/**
 * Keeps cellular data attached while another network is the default, for as long as anything
 * [holds][hold] it.
 *
 * Android brings cellular data down while Wi-Fi is the default network and brings it back only after
 * it has declared Wi-Fi lost. One `ConnectivityManager.requestNetwork` for `TRANSPORT_CELLULAR` +
 * `NET_CAPABILITY_INTERNET` keeps it up; that request is registered when the first hold is taken and
 * unregistered when the last is closed, however many holders share it. [state] names the network the
 * platform produced, which a socket can be pinned to with `Network.bindSocket` while the default route
 * still points elsewhere.
 *
 * Holding costs standby battery: the modem keeps a data bearer instead of detaching. It costs no data
 * until something sends over it.
 *
 * Requires `android.permission.CHANGE_NETWORK_STATE` (declared by this module's manifest). Without it,
 * or without an application `Context`, the hold is still counted and [state] reports
 * [CellularStandbyState.Refused].
 */
class CellularStandby internal constructor(
    private val registrar: StandbyRegistrar,
) {
    private val _state = MutableStateFlow<CellularStandbyState>(CellularStandbyState.Released)

    /** What the shared request is doing now. Every transition is one compare-and-set on this value. */
    val state: StateFlow<CellularStandbyState> = _state.asStateFlow()

    /** Take a hold. The request is registered on the first one; close the returned hold to release it. */
    fun hold(): CellularStandbyHold {
        acquire()
        return CellularStandbyHold(this)
    }

    private fun acquire() {
        while (true) {
            val current = _state.value
            val next =
                when (current) {
                    CellularStandbyState.Released -> CellularStandbyState.Requesting(1, StandbyRegistration(this))
                    is CellularStandbyState.Requesting -> CellularStandbyState.Requesting(current.holders + 1, current.registration)
                    is CellularStandbyState.Attached ->
                        CellularStandbyState.Attached(current.holders + 1, current.network, current.link, current.registration)
                    is CellularStandbyState.Refused -> CellularStandbyState.Refused(current.holders + 1, current.reason)
                }
            if (!_state.compareAndSet(current, next)) continue
            if (next is CellularStandbyState.Requesting && current == CellularStandbyState.Released) register(next.registration)
            return
        }
    }

    internal fun release() {
        while (true) {
            val current = _state.value
            val next: CellularStandbyState
            val retiring: StandbyRegistration?
            when (current) {
                CellularStandbyState.Released -> error("a standby hold was released more times than it was taken")
                is CellularStandbyState.Requesting -> {
                    next =
                        if (current.holders ==
                            1
                        ) {
                            CellularStandbyState.Released
                        } else {
                            CellularStandbyState.Requesting(current.holders - 1, current.registration)
                        }
                    retiring = current.registration.takeIf { current.holders == 1 }
                }
                is CellularStandbyState.Attached -> {
                    next =
                        if (current.holders == 1) {
                            CellularStandbyState.Released
                        } else {
                            CellularStandbyState.Attached(current.holders - 1, current.network, current.link, current.registration)
                        }
                    retiring = current.registration.takeIf { current.holders == 1 }
                }
                is CellularStandbyState.Refused -> {
                    next =
                        if (current.holders ==
                            1
                        ) {
                            CellularStandbyState.Released
                        } else {
                            CellularStandbyState.Refused(current.holders - 1, current.reason)
                        }
                    retiring = null
                }
            }
            if (!_state.compareAndSet(current, next)) continue
            if (retiring != null && retiring.retire()) registrar.unregister(retiring.callback)
            return
        }
    }

    /**
     * Register [registration] with the platform. A release can race this: it may retire the
     * registration before the platform call returns, in which case the registration unregisters itself
     * here, so the request is never left behind with no holder.
     */
    private fun register(registration: StandbyRegistration) {
        when (val outcome = registrar.register(registration.callback)) {
            StandbyRegistrar.Outcome.Registered -> if (!registration.markRegistered()) registrar.unregister(registration.callback)
            is StandbyRegistrar.Outcome.Refused ->
                _state.update { current ->
                    if (current is CellularStandbyState.Requesting && current.registration === registration) {
                        CellularStandbyState.Refused(current.holders, outcome.reason)
                    } else {
                        current
                    }
                }
        }
    }

    internal fun onCapabilities(
        registration: StandbyRegistration,
        network: Network,
        link: NetworkState,
    ) {
        _state.update { current ->
            when {
                current is CellularStandbyState.Requesting && current.registration === registration ->
                    CellularStandbyState.Attached(current.holders, network, link, registration)
                current is CellularStandbyState.Attached && current.registration === registration ->
                    CellularStandbyState.Attached(current.holders, network, link, registration)
                else -> current
            }
        }
    }

    internal fun onLost(
        registration: StandbyRegistration,
        network: Network,
    ) {
        _state.update { current ->
            if (current is CellularStandbyState.Attached && current.registration === registration && current.network == network) {
                CellularStandbyState.Requesting(current.holders, registration)
            } else {
                current
            }
        }
    }

    companion object {
        private val processWide: CellularStandby by lazy { CellularStandby(ApplicationContextRegistrar) }

        /**
         * The one [CellularStandby] for the process, using the application `Context` this module
         * captured at startup (see [NetworkMonitor.Companion.installAndroidApplicationContext]). The
         * `Context` is read when a request is registered, not when this is first called.
         */
        fun processDefault(): CellularStandby = processWide
    }
}

/** One holder's claim on a [CellularStandby]. [close] releases it; closing twice releases once. */
class CellularStandbyHold internal constructor(
    private val standby: CellularStandby,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** The shared request's state; the same value for every holder. */
    val state: StateFlow<CellularStandbyState> get() = standby.state

    override fun close() {
        if (closed.compareAndSet(false, true)) standby.release()
    }
}

/**
 * What a [CellularStandby]'s shared request is doing. Every state but [Released] counts its
 * [holders][Held.holders].
 */
sealed interface CellularStandbyState {
    /** Nothing holds the standby link, so no request is registered. */
    data object Released : CellularStandbyState

    /** A state in which at least one hold is open. */
    sealed interface Held : CellularStandbyState {
        val holders: Int
    }

    /** The request is registered and the platform has not produced a cellular network for it yet. */
    class Requesting internal constructor(
        override val holders: Int,
        internal val registration: StandbyRegistration,
    ) : Held {
        override fun toString(): String = "Requesting(holders=$holders)"
    }

    /**
     * The platform produced [network] for the request, and [link] is its latest capabilities as the
     * same ladder [AndroidNetworkMonitor] publishes. A socket pinned to [network] with
     * `Network.bindSocket` routes over it whatever the default network is.
     */
    class Attached internal constructor(
        override val holders: Int,
        val network: Network,
        val link: NetworkState,
        internal val registration: StandbyRegistration,
    ) : Held {
        override fun toString(): String = "Attached(holders=$holders, link=$link)"
    }

    /** The platform refused the request, for [reason]. Held until the last holder closes; the next hold asks again. */
    class Refused internal constructor(
        override val holders: Int,
        val reason: CellularStandbyRefusal,
    ) : Held {
        override fun toString(): String = "Refused(holders=$holders, reason=$reason)"
    }
}

/** Why a [CellularStandby] request could not be registered. */
sealed interface CellularStandbyRefusal {
    /**
     * `ConnectivityManager.requestNetwork` threw: the merged manifest lacks
     * `android.permission.CHANGE_NETWORK_STATE`. [cause] is the platform's exception.
     */
    data class MissingPermission(
        val cause: SecurityException,
    ) : CellularStandbyRefusal

    /** No application `Context` was captured, so there is no `ConnectivityManager` to ask. */
    data object NoApplicationContext : CellularStandbyRefusal
}

/**
 * One registration of the shared request, and the callback the platform reports on. Its [phase] makes
 * register and unregister happen once each, in that order, whichever of an acquire and a release
 * reaches the platform first.
 */
internal class StandbyRegistration(
    private val owner: CellularStandby,
) {
    private sealed interface Phase {
        data object Pending : Phase

        data object Registered : Phase

        data object Retired : Phase
    }

    private val phase = AtomicReference<Phase>(Phase.Pending)

    /** Whether the platform call completed before a release retired this registration. */
    fun markRegistered(): Boolean = phase.compareAndSet(Phase.Pending, Phase.Registered)

    /** Retire this registration. Returns whether it was registered, and so is the caller's to unregister. */
    fun retire(): Boolean = phase.getAndSet(Phase.Retired) == Phase.Registered

    val callback: ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                caps: AndroidNetworkCapabilities,
            ) = owner.onCapabilities(this@StandbyRegistration, network, standbyLinkState(network, caps))

            override fun onLost(network: Network) = owner.onLost(this@StandbyRegistration, network)
        }
}

/** The request's capabilities on the same ladder [AndroidNetworkMonitor] publishes the default network on. */
private fun standbyLinkState(
    network: Network,
    caps: AndroidNetworkCapabilities,
): NetworkState =
    androidNetworkState(
        id =
            androidNetworkId(
                hasWifi = caps.hasTransport(AndroidNetworkCapabilities.TRANSPORT_WIFI),
                hasCellular = caps.hasTransport(AndroidNetworkCapabilities.TRANSPORT_CELLULAR),
                hasEthernet = caps.hasTransport(AndroidNetworkCapabilities.TRANSPORT_ETHERNET),
                hasVpn = caps.hasTransport(AndroidNetworkCapabilities.TRANSPORT_VPN),
                handle = network.networkHandle,
            ),
        hasInternet = caps.hasCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET),
        hasValidated = caps.hasCapability(AndroidNetworkCapabilities.NET_CAPABILITY_VALIDATED),
        hasCaptivePortal = caps.hasCapability(AndroidNetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        // Below API 28 the bit does not exist and its absence would read as suspended; see AndroidNetworkMonitor.
        notSuspended =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                caps.hasCapability(AndroidNetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
        blockedForApp = false,
    )

/** The platform half of a [CellularStandby]: registers and unregisters one callback. A seam for tests. */
internal interface StandbyRegistrar {
    sealed interface Outcome {
        data object Registered : Outcome

        data class Refused(
            val reason: CellularStandbyRefusal,
        ) : Outcome
    }

    fun register(callback: ConnectivityManager.NetworkCallback): Outcome

    fun unregister(callback: ConnectivityManager.NetworkCallback)
}

/** The cellular + `INTERNET` request every [CellularStandby] registers. */
internal fun cellularStandbyRequest(): NetworkRequest =
    NetworkRequest
        .Builder()
        .addTransportType(AndroidNetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

/** [StandbyRegistrar] over one [ConnectivityManager]. */
internal class ConnectivityManagerRegistrar(
    private val connectivityManager: ConnectivityManager,
) : StandbyRegistrar {
    override fun register(callback: ConnectivityManager.NetworkCallback): StandbyRegistrar.Outcome =
        try {
            connectivityManager.requestNetwork(cellularStandbyRequest(), callback)
            StandbyRegistrar.Outcome.Registered
        } catch (e: SecurityException) {
            StandbyRegistrar.Outcome.Refused(CellularStandbyRefusal.MissingPermission(e))
        }

    override fun unregister(callback: ConnectivityManager.NetworkCallback) {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}

/**
 * [StandbyRegistrar] over the captured application `Context`, read at registration so a `Context`
 * installed after [CellularStandby.processDefault] was first called is still used.
 */
private object ApplicationContextRegistrar : StandbyRegistrar {
    override fun register(callback: ConnectivityManager.NetworkCallback): StandbyRegistrar.Outcome {
        val context =
            androidApplicationContext()
                ?: return StandbyRegistrar.Outcome.Refused(CellularStandbyRefusal.NoApplicationContext)
        return ConnectivityManagerRegistrar(context.connectivityManager()).register(callback)
    }

    override fun unregister(callback: ConnectivityManager.NetworkCallback) {
        // Only a registered callback is ever unregistered, and registering needed a Context.
        val context = checkNotNull(androidApplicationContext()) { "a standby request was registered without a Context" }
        ConnectivityManagerRegistrar(context.connectivityManager()).unregister(callback)
    }

    private fun Context.connectivityManager(): ConnectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
}
