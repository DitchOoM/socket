package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import android.net.NetworkCapabilities as AndroidNetworkCapabilities

/**
 * One `ConnectivityManager.requestNetwork` for [radio] + `NET_CAPABILITY_INTERNET`, registered by [open]
 * and unregistered by [close]. [state] names the network the platform produced, which a socket bound
 * to it with `Network.bindSocket` rides whatever the default network is. While the request is open the
 * platform keeps that network up — for cellular, beside Wi-Fi, as [CellularStandby] does.
 *
 * Every open request is one platform callback, and Android caps an app at 100: close it with whatever
 * it pins. Requires `android.permission.CHANGE_NETWORK_STATE` (declared by this module's manifest).
 */
class RadioNetworkRequest internal constructor(
    val radio: NetworkKind.Radio,
    private val connectivityManager: ConnectivityManager?,
) : AutoCloseable {
    /** What is asked of the platform: [radio]'s transport, reaching the internet. */
    internal val request: NetworkRequest = internetRequestOn(radio.androidTransport)

    private val _state = MutableStateFlow<RadioNetworkState>(RadioNetworkState.Requesting)

    /** What the request is doing now. Every transition is one compare-and-set on this value. */
    val state: StateFlow<RadioNetworkState> = _state.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) =
                _state.update { current ->
                    when (current) {
                        RadioNetworkState.Requesting, is RadioNetworkState.Attached -> RadioNetworkState.Attached(network)
                        is RadioNetworkState.Refused, RadioNetworkState.Closed -> current
                    }
                }

            override fun onLost(network: Network) =
                _state.update { current ->
                    if (current is RadioNetworkState.Attached && current.network == network) RadioNetworkState.Requesting else current
                }
        }

    internal fun register() {
        val manager =
            connectivityManager
                ?: return _state.update { RadioNetworkState.Refused(RadioRequestRefusal.NoApplicationContext) }
        try {
            manager.requestNetwork(request, callback)
        } catch (e: SecurityException) {
            _state.update { RadioNetworkState.Refused(RadioRequestRefusal.MissingPermission(e)) }
        }
    }

    /** Unregister the request. Closing twice unregisters once. */
    override fun close() {
        when (_state.getAndUpdate { RadioNetworkState.Closed }) {
            RadioNetworkState.Requesting, is RadioNetworkState.Attached ->
                checkNotNull(
                    connectivityManager,
                ).unregisterNetworkCallback(callback)
            // Never registered, or already unregistered.
            is RadioNetworkState.Refused, RadioNetworkState.Closed -> Unit
        }
    }

    companion object {
        /**
         * Register a request for [radio] through the application `Context` this module captured at startup
         * (see [NetworkMonitor.Companion.installAndroidApplicationContext]).
         */
        fun open(radio: NetworkKind.Radio): RadioNetworkRequest =
            RadioNetworkRequest(radio, androidApplicationContext()?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?)
                .also { it.register() }
    }
}

/** What a [RadioNetworkRequest] is doing. */
sealed interface RadioNetworkState {
    /** Registered, and the platform has not produced a network for it, or lost the one it had. */
    data object Requesting : RadioNetworkState

    /** Every state but [Requesting]: something has answered the request. */
    sealed interface Answered : RadioNetworkState

    /** The platform produced [network] for the request. */
    class Attached internal constructor(
        val network: Network,
    ) : Answered {
        override fun toString(): String = "Attached($network)"
    }

    /** The platform refused the request, for [reason]; nothing is registered. */
    data class Refused(
        val reason: RadioRequestRefusal,
    ) : Answered

    /** [RadioNetworkRequest.close] ran. */
    data object Closed : Answered
}

/** Why a [RadioNetworkRequest] could not be registered. */
sealed interface RadioRequestRefusal {
    /** `requestNetwork` threw: the merged manifest lacks `android.permission.CHANGE_NETWORK_STATE`. */
    data class MissingPermission(
        val cause: SecurityException,
    ) : RadioRequestRefusal

    /** No application `Context` was captured, so there is no `ConnectivityManager` to ask. */
    data object NoApplicationContext : RadioRequestRefusal
}

/** A request for a network on [transport] (`NetworkCapabilities.TRANSPORT_*`) that reaches the internet. */
internal fun internetRequestOn(transport: Int): NetworkRequest =
    NetworkRequest
        .Builder()
        .addTransportType(transport)
        .addCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

private val NetworkKind.Radio.androidTransport: Int
    get() =
        when (this) {
            NetworkKind.Wifi -> AndroidNetworkCapabilities.TRANSPORT_WIFI
            NetworkKind.Cellular -> AndroidNetworkCapabilities.TRANSPORT_CELLULAR
        }
