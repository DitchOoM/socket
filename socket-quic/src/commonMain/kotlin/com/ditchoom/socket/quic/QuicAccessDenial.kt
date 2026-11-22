package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkAccess

/**
 * Why the operating system keeps this app from reaching a peer, and how that is known: [Reported] when the platform
 * says so, [Likely] when it is read from how the system refused the connect or the sends. One vocabulary on every
 * platform.
 */
sealed interface QuicAccessDenial {
    /** The access the system withholds. */
    val access: NetworkAccess

    /** What gives the access back. Branch on it to show your own text. */
    val remedy: QuicAccessRemedy

    /** The platform itself says the access is withheld: retrying cannot help until it is given back. */
    sealed interface Reported : QuicAccessDenial

    /**
     * The system refused a peer on the local network the way it refuses an app without local-network access, and said
     * no more: very likely the denial, though another cause cannot be ruled out.
     */
    sealed interface Likely : QuicAccessDenial

    /**
     * Android 17 (API 37) and later, for an app targeting API 37 or higher: the app does not hold
     * `android.permission.ACCESS_LOCAL_NETWORK`, and the system refused every send to a peer on the local network.
     */
    data object AndroidPermissionNotHeld : Reported {
        override val access: NetworkAccess get() = NetworkAccess.LocalNetwork
        override val remedy: QuicAccessRemedy get() = QuicAccessRemedy.RequestAndroidLocalNetworkPermission
    }

    /**
     * Android 17 (API 37) and later: the system refused every send to a peer on the local network as not permitted, and
     * the app's permission could not be read because no application `Context` was captured. Without a `Context` the
     * app's target SDK is not known either, so an app targeting below API 37, which the permission does not apply to,
     * whose sends Data Saver or a firewall refused, is reported so too.
     */
    data object AndroidSendsNotPermitted : Likely {
        override val access: NetworkAccess get() = NetworkAccess.LocalNetwork
        override val remedy: QuicAccessRemedy get() = QuicAccessRemedy.RequestAndroidLocalNetworkPermission
    }

    /**
     * Network.framework found no path to the peer because the user withholds [access] from this app: Local Network
     * privacy, or cellular or Wi-Fi data turned off for it.
     */
    data class AppleSettings(
        override val access: NetworkAccess,
    ) : Reported {
        override val remedy: QuicAccessRemedy
            get() =
                when (access) {
                    NetworkAccess.LocalNetwork -> QuicAccessRemedy.AllowLocalNetworkInAppleSettings
                    NetworkAccess.CellularData -> QuicAccessRemedy.AllowCellularDataInAppleSettings
                    NetworkAccess.WifiData -> QuicAccessRemedy.AllowWifiDataInAppleSettings
                }
    }

    /**
     * macOS: the system refused, with no route to host, the first connect this process made to a peer on a network this
     * host is attached to. That is how Local Network privacy refuses an app or executable without the grant. Refused
     * sends are never read so: a neighbour that stopped answering draws the same refusal of a new socket's first sends.
     */
    data object MacosNoRouteOnLink : Likely {
        override val access: NetworkAccess get() = NetworkAccess.LocalNetwork
        override val remedy: QuicAccessRemedy get() = QuicAccessRemedy.AllowLocalNetworkInMacosSettings
    }
}

/** What gives a withheld network access back. */
enum class QuicAccessRemedy {
    /** Declare `android.permission.ACCESS_LOCAL_NETWORK` in the manifest and request it at runtime. */
    RequestAndroidLocalNetworkPermission,

    /** Allow the app in Settings > Privacy & Security > Local Network; its `Info.plist` declares `NSLocalNetworkUsageDescription`. */
    AllowLocalNetworkInAppleSettings,

    /** Turn on cellular data for the app in Settings. */
    AllowCellularDataInAppleSettings,

    /** Turn on Wi-Fi data for the app in Settings. */
    AllowWifiDataInAppleSettings,

    /**
     * Allow the app, or the `java` binary that runs it, in System Settings > Privacy & Security > Local Network. A bundled
     * JVM needs `NSLocalNetworkUsageDescription` in the app's `Info.plist`, one signing identity across its executables,
     * and the app that launched it still running.
     */
    AllowLocalNetworkInMacosSettings,
}

/** The remedy as a close message names it. */
internal fun QuicAccessRemedy.summary(): String =
    when (this) {
        QuicAccessRemedy.RequestAndroidLocalNetworkPermission ->
            "declare android.permission.ACCESS_LOCAL_NETWORK in the manifest and request it at runtime"
        QuicAccessRemedy.AllowLocalNetworkInAppleSettings ->
            "allow this app in Settings > Privacy & Security > Local Network; " +
                "the app declares NSLocalNetworkUsageDescription in its Info.plist"
        QuicAccessRemedy.AllowCellularDataInAppleSettings -> "turn on cellular data for this app in Settings"
        QuicAccessRemedy.AllowWifiDataInAppleSettings -> "turn on Wi-Fi data for this app in Settings"
        QuicAccessRemedy.AllowLocalNetworkInMacosSettings ->
            "allow the app, or the java binary that runs it, in System Settings > Privacy & Security > Local Network"
    }
