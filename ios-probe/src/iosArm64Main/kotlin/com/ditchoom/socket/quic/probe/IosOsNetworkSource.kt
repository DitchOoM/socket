package com.ditchoom.socket.quic.probe

import com.ditchoom.socket.enumerateNetworkInterfaces
import com.ditchoom.socket.testkit.osnet.CellularBearer
import com.ditchoom.socket.testkit.osnet.CellularData
import com.ditchoom.socket.testkit.osnet.CellularRegistration
import com.ditchoom.socket.testkit.osnet.CellularRoaming
import com.ditchoom.socket.testkit.osnet.CellularSignal
import com.ditchoom.socket.testkit.osnet.CellularStatus
import com.ditchoom.socket.testkit.osnet.OsNetworkReading
import com.ditchoom.socket.testkit.osnet.OsNetworkSource
import com.ditchoom.socket.testkit.osnet.SimState
import com.ditchoom.socket.testkit.osnet.osLinksFrom
import platform.CoreTelephony.CTRadioAccessTechnologyCDMA1x
import platform.CoreTelephony.CTRadioAccessTechnologyCDMAEVDORev0
import platform.CoreTelephony.CTRadioAccessTechnologyCDMAEVDORevA
import platform.CoreTelephony.CTRadioAccessTechnologyCDMAEVDORevB
import platform.CoreTelephony.CTRadioAccessTechnologyDidChangeNotification
import platform.CoreTelephony.CTRadioAccessTechnologyEdge
import platform.CoreTelephony.CTRadioAccessTechnologyGPRS
import platform.CoreTelephony.CTRadioAccessTechnologyHSDPA
import platform.CoreTelephony.CTRadioAccessTechnologyHSUPA
import platform.CoreTelephony.CTRadioAccessTechnologyLTE
import platform.CoreTelephony.CTRadioAccessTechnologyNR
import platform.CoreTelephony.CTRadioAccessTechnologyNRNSA
import platform.CoreTelephony.CTRadioAccessTechnologyWCDMA
import platform.CoreTelephony.CTRadioAccessTechnologyeHRPD
import platform.CoreTelephony.CTTelephonyNetworkInfo
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol
import kotlin.concurrent.AtomicReference

/**
 * What iOS will say about this device's network beyond the ladder.
 *
 * It is considerably less than Android, and the gap is itself the finding. CoreTelephony's only live
 * public signal is `serviceCurrentRadioAccessTechnology` — the radio **technology** — so that is the
 * one cellular fact recorded. There is no public API for registration state, data connection state
 * or roaming on iOS at any version, and `CTCarrier` (which used to carry an ISO country code a
 * roaming check could be built on) has been deprecated since iOS 16 and returns placeholder values
 * for every app, so reading it would produce a fabricated answer rather than a missing one. Each of
 * those four therefore reports [CellularRegistration.NotReported] and its peers, every time, on
 * purpose.
 *
 * Links and their addresses come from `getifaddrs` through
 * [enumerateNetworkInterfaces][com.ditchoom.socket.enumerateNetworkInterfaces], the same scan every
 * other platform uses — which is where an iPhone's answer to "does this device have IPv6" comes from.
 *
 * Radio changes are pushed: `CTRadioAccessTechnologyDidChangeNotification` fires on every technology
 * change, so [cellularSignal] is [CellularSignal.Signalled] once [register] has run.
 */
class IosOsNetworkSource : OsNetworkSource {
    // Held for the source's life: the notification fires against this instance, and a released
    // CTTelephonyNetworkInfo stops delivering.
    /** Whether CoreTelephony is pushing technology changes, and the token needed to stop it. */
    private sealed interface Push {
        data object Off : Push

        data class On(
            val token: NSObjectProtocol,
        ) : Push
    }

    private val telephony = CTTelephonyNetworkInfo()
    private val push = AtomicReference<Push>(Push.Off)

    override val cellularSignal: CellularSignal
        get() = if (push.value is Push.On) CellularSignal.Signalled else CellularSignal.Sampled

    /** Ask CoreTelephony to push technology changes at [onChange]. Idempotent. */
    fun register(onChange: () -> Unit): CellularSignal {
        if (push.value is Push.On) return cellularSignal
        val token =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = CTRadioAccessTechnologyDidChangeNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ -> onChange() }
        if (!push.compareAndSet(Push.Off, Push.On(token))) NSNotificationCenter.defaultCenter.removeObserver(token)
        return cellularSignal
    }

    /** Stop pushing. Idempotent; a source that never registered has nothing to release. */
    fun unregister() {
        val on = push.value as? Push.On ?: return
        if (push.compareAndSet(on, Push.Off)) NSNotificationCenter.defaultCenter.removeObserver(on.token)
    }

    override fun read(): OsNetworkReading =
        OsNetworkReading(
            cellular =
                CellularStatus.Reported(
                    sim = SimState.NotReported,
                    registration = CellularRegistration.NotReported,
                    data = CellularData.NotReported,
                    roaming = CellularRoaming.NotReported,
                    bearer = currentBearer(),
                ),
            links = osLinksFrom(enumerateNetworkInterfaces()),
        )

    /**
     * The technology of the first service reporting one, folded onto the generations a walk reads.
     * A dual-SIM iPhone reports one entry per service; only one carries data at a time and
     * CoreTelephony gives no way to say which, so the first reported one is recorded rather than a
     * synthesised "both". No entry at all is the platform answering that no bearer is up.
     */
    private fun currentBearer(): CellularBearer {
        val reported = telephony.serviceCurrentRadioAccessTechnology?.values?.firstOrNull { it is String }
        return if (reported is String) bearerOf(reported) else CellularBearer.None
    }
}

/** CoreTelephony's own constant for the technology, folded onto the generations a walk reads. */
private fun bearerOf(raw: String): CellularBearer =
    when (raw) {
        CTRadioAccessTechnologyGPRS, CTRadioAccessTechnologyEdge -> CellularBearer.Gsm
        CTRadioAccessTechnologyWCDMA, CTRadioAccessTechnologyHSDPA, CTRadioAccessTechnologyHSUPA -> CellularBearer.Umts
        CTRadioAccessTechnologyCDMA1x,
        CTRadioAccessTechnologyCDMAEVDORev0,
        CTRadioAccessTechnologyCDMAEVDORevA,
        CTRadioAccessTechnologyCDMAEVDORevB,
        CTRadioAccessTechnologyeHRPD,
        -> CellularBearer.Cdma
        CTRadioAccessTechnologyLTE -> CellularBearer.Lte
        CTRadioAccessTechnologyNR, CTRadioAccessTechnologyNRNSA -> CellularBearer.Nr
        else -> CellularBearer.Other(raw)
    }
