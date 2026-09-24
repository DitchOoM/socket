package com.ditchoom.socket.quic

/**
 * Whether a client connection keeps a second link attached while the default one carries its traffic,
 * so that a path which stops answering can move without waiting for the OS to declare the default
 * link lost. Read only under [MigrationPolicy.Automatic], by a client.
 *
 * The OS signal arrives late. On Android a Wi-Fi link can pass nothing for tens of seconds before
 * `ConnectivityManager` reports it lost, and only then does the platform bring cellular data up, which
 * can take tens of seconds more. The connection's own dead-path detection notices within a few
 * seconds, but a migration then has nowhere to go: the default route still points at the dead Wi-Fi
 * link, and cellular is not attached.
 *
 * | Platform | [KeepCellularReady] | [OnDemand] |
 * |---|---|---|
 * | Android | holds a `ConnectivityManager.requestNetwork` for cellular + `INTERNET` while any such connection is open | nothing is requested |
 * | Apple | no-op: iOS keeps the cellular interface up beside Wi-Fi itself | no-op |
 * | JVM, Linux | no-op: the host has no standby radio to hold | no-op |
 */
sealed interface StandbyLink {
    /**
     * On Android, keep cellular data attached while at least one connection with this policy is open,
     * and move onto it as soon as the connection detects that its path has stopped answering. Nothing
     * is sent over cellular until then. The default, because it gives Android the same standby link
     * iOS already has.
     *
     * **Cost.** This is the mechanism behind Android's "Mobile data always active" developer setting:
     * the modem keeps a data bearer up instead of detaching while on Wi-Fi. The radio idles in its
     * low-power connected state between packets, so the cost is standby battery drain, not data. The
     * request is shared by every connection in the process and released when the last one closes.
     *
     * **Permission.** Requires `android.permission.CHANGE_NETWORK_STATE`, a normal permission that
     * `com.ditchoom:network-monitor`'s manifest already declares. If an app strips it, the request is
     * refused and the refusal is reported as a typed state on `CellularStandby.state`; migration then
     * behaves as [OnDemand].
     */
    data object KeepCellularReady : StandbyLink

    /**
     * Keep nothing attached beyond what the OS chooses. A dead path moves only to the default route,
     * so on Android a Wi-Fi link that dies without the OS noticing is not left until the OS brings
     * cellular up.
     */
    data object OnDemand : StandbyLink
}
