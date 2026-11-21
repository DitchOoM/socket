package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkAccess

/**
 * Why the operating system keeps this app from reaching a peer, and how that is known: [Reported] when the platform
 * says so, [Likely] when it is read from how the system refused the sends. One vocabulary on every platform.
 */
sealed interface QuicAccessDenial {
    /** The access the system withholds. */
    val access: NetworkAccess

    /** What gives the access back, for a developer or a user to act on. Documentation: branch on the type. */
    val remedy: String

    /** The platform itself says the access is withheld: retrying cannot help until it is given back. */
    sealed interface Reported : QuicAccessDenial

    /**
     * Every send to a peer on the local network was refused the way the system refuses an app without local-network
     * access, and the system said no more: very likely the denial, though another cause cannot be ruled out.
     */
    sealed interface Likely : QuicAccessDenial

    /**
     * Android 17 (API 37) and later, for an app targeting API 37 or higher: the app does not hold
     * `android.permission.ACCESS_LOCAL_NETWORK`, and the system refused a send to the peer.
     */
    data object AndroidPermissionNotHeld : Reported {
        override val access: NetworkAccess get() = NetworkAccess.LocalNetwork
        override val remedy: String get() = ANDROID_REMEDY
    }

    /**
     * Android 17 (API 37) and later: the system refused every send to a local-network peer as not permitted, and the
     * app's permission could not be read because no application `Context` was captured.
     */
    data object AndroidSendsNotPermitted : Likely {
        override val access: NetworkAccess get() = NetworkAccess.LocalNetwork
        override val remedy: String get() = ANDROID_REMEDY
    }

    /**
     * Network.framework found no path to the peer because the user withholds [access] from this app: Local Network
     * privacy, or cellular or Wi-Fi data turned off for it.
     */
    data class AppleSettings(
        override val access: NetworkAccess,
    ) : Reported {
        override val remedy: String
            get() =
                when (access) {
                    NetworkAccess.LocalNetwork ->
                        "allow this app in Settings > Privacy & Security > Local Network; " +
                            "the app declares NSLocalNetworkUsageDescription in its Info.plist"
                    NetworkAccess.CellularData -> "turn on cellular data for this app in Settings"
                    NetworkAccess.WifiData -> "turn on Wi-Fi data for this app in Settings"
                }
    }

    /**
     * macOS: the system refused at once, with no route to host, the connect or every send to a peer on a network this
     * host is attached to. That is how Local Network privacy refuses an app or executable without the grant; a host on
     * an attached network that does not answer is refused only after its first datagrams were taken.
     */
    data object MacosNoRouteOnLink : Likely {
        override val access: NetworkAccess get() = NetworkAccess.LocalNetwork
        override val remedy: String
            get() =
                "allow the app, or the java binary that runs it, in System Settings > Privacy & Security > Local Network; " +
                    "a bundled JVM needs NSLocalNetworkUsageDescription in the app's Info.plist, one signing identity " +
                    "across its executables, and the app that launched it still running"
    }
}

private const val ANDROID_REMEDY =
    "declare android.permission.ACCESS_LOCAL_NETWORK in the manifest and request it at runtime"
