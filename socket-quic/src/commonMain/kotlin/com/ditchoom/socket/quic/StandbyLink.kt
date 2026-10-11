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
     * move onto it as soon as the connection detects that its path has stopped answering, and move
     * back onto the platform's default link as soon as that link answers again. The default, because
     * it gives Android the same standby link iOS already has.
     *
     * **The way back.** The platform usually reports nothing when a link that went quiet for a few
     * seconds recovers, so the connection asks for itself: while it is on cellular and the platform
     * names another link as its default, it probes that link 1s after the move, then at doubling
     * intervals up to once a minute, and migrates back on the first probe that validates. A failed probe
     * leaves the connection on cellular, which is working. Any change the platform reports restarts the
     * schedule from 1s. A link that cannot hold the connection after a return pushes the next return
     * later, so a flapping link is not moved onto at full rate.
     *
     * **Cost.** This is the mechanism behind Android's "Mobile data always active" developer setting:
     * the modem keeps a data bearer up instead of detaching while on Wi-Fi. The radio idles in its
     * low-power connected state between packets, so while the connection is on its default link the cost
     * is standby battery drain, not data. Traffic crosses cellular only from the move until a probe of
     * the default link validates. The request is shared by every connection in the process and released
     * when the last one closes.
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
