package com.ditchoom.socket.quic

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
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

/**
 * What Android will say about this device's network beyond the ladder — the radio, and the links
 * with their addresses.
 *
 * Every read is independently guarded. `getSimState`, `getDataState` and `isNetworkRoaming` need no
 * permission; `getServiceState` and `getDataNetworkType` need `READ_PHONE_STATE`, which is declared
 * in the instrumented-test manifest but is a **runtime** grant on API 23+:
 * ```
 * adb shell pm grant com.ditchoom.socket.quic.quiche.test android.permission.READ_PHONE_STATE
 * ```
 * Without the grant those two report their typed absence and the rest still reports, which is the
 * whole reason each field carries its own — a walk that recorded four facts and a blank would be
 * indistinguishable from one that recorded four facts about an idle radio.
 *
 * [register]'s callback is invoked from the platform's telephony callback (API 31+) on the main
 * executor, and the caller samples from it. Below API 31 nothing is registered and [cellularSignal]
 * says [CellularSignal.Sampled] out loud, so a later reader knows a telephony-only change on that
 * device was recorded at the next sample rather than when it happened.
 */
class AndroidOsNetworkSource(
    private val context: Context,
) : OsNetworkSource {
    /** Whether this device has a cellular radio to describe at all. */
    private sealed interface Radio {
        data object Absent : Radio

        data class Present(
            val manager: TelephonyManager,
        ) : Radio
    }

    /** Whether the platform is pushing radio changes, and the token needed to stop it. */
    private sealed interface Push {
        data object Off : Push

        data class On(
            val callback: TelephonyCallback,
        ) : Push
    }

    private val radio: Radio =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.let(Radio::Present) ?: Radio.Absent
        } else {
            Radio.Absent
        }

    // Written by register/unregister on the probe's thread, read by cellularSignal on another.
    @Volatile
    private var push: Push = Push.Off

    override val cellularSignal: CellularSignal
        get() =
            when (radio) {
                Radio.Absent -> CellularSignal.NotReported
                is Radio.Present -> if (push is Push.On) CellularSignal.Signalled else CellularSignal.Sampled
            }

    /**
     * Ask the platform to push radio changes at [onChange]. Best-effort by construction: a device
     * below API 31, or one that withheld `READ_PHONE_STATE`, keeps working at the sampling cadence
     * and says so through [cellularSignal] — a probe whose whole contract is that the recording is
     * the deliverable must not fail to start over an instrument.
     */
    fun register(onChange: () -> Unit): CellularSignal {
        val present = radio as? Radio.Present ?: return CellularSignal.NotReported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return CellularSignal.Sampled
        runCatching {
            val callback = RadioCallback(onChange)
            present.manager.registerTelephonyCallback(context.mainExecutor, callback)
            push = Push.On(callback)
        }
        return cellularSignal
    }

    /** Stop pushing. Idempotent; a probe that never registered has nothing to release. */
    fun unregister() {
        val on = push as? Push.On ?: return
        val present = radio as? Radio.Present ?: return
        push = Push.Off
        runCatching { present.manager.unregisterTelephonyCallback(on.callback) }
    }

    override fun read(): OsNetworkReading =
        OsNetworkReading(
            cellular =
                when (radio) {
                    Radio.Absent -> CellularStatus.NotReported
                    is Radio.Present -> readCellular(radio.manager)
                },
            links = runCatching { osLinksFrom(enumerateNetworkInterfaces()) }.getOrElse { emptyList() },
        )

    private fun readCellular(manager: TelephonyManager): CellularStatus =
        CellularStatus.Reported(
            sim = runCatching { simState(manager.simState) }.getOrElse { SimState.NotReported },
            registration =
                runCatching { registrationOf(manager.serviceState) }
                    .getOrElse { CellularRegistration.NotReported },
            data = runCatching { dataState(manager.dataState) }.getOrElse { CellularData.NotReported },
            roaming =
                runCatching { if (manager.isNetworkRoaming) CellularRoaming.Roaming else CellularRoaming.Home }
                    .getOrElse { CellularRoaming.NotReported },
            bearer = runCatching { bearerOf(manager.dataNetworkType) }.getOrElse { CellularBearer.NotReported },
        )

    /** The platform's telephony push, kept in its own class so the API-31 type is only ever loaded above it. */
    private class RadioCallback(
        private val onChange: () -> Unit,
    ) : TelephonyCallback(),
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DataConnectionStateListener {
        override fun onServiceStateChanged(serviceState: ServiceState) = onChange()

        override fun onDataConnectionStateChanged(
            state: Int,
            networkType: Int,
        ) = onChange()
    }
}

private fun simState(raw: Int): SimState =
    when (raw) {
        TelephonyManager.SIM_STATE_ABSENT -> SimState.Absent
        TelephonyManager.SIM_STATE_PIN_REQUIRED,
        TelephonyManager.SIM_STATE_PUK_REQUIRED,
        TelephonyManager.SIM_STATE_NETWORK_LOCKED,
        -> SimState.Locked
        TelephonyManager.SIM_STATE_READY -> SimState.Ready
        else -> SimState.NotReported
    }

private fun registrationOf(state: ServiceState?): CellularRegistration =
    when (state?.state) {
        ServiceState.STATE_IN_SERVICE -> CellularRegistration.InService
        ServiceState.STATE_OUT_OF_SERVICE -> CellularRegistration.OutOfService
        ServiceState.STATE_EMERGENCY_ONLY -> CellularRegistration.EmergencyOnly
        ServiceState.STATE_POWER_OFF -> CellularRegistration.PowerOff
        else -> CellularRegistration.NotReported
    }

private fun dataState(raw: Int): CellularData =
    when (raw) {
        TelephonyManager.DATA_DISCONNECTED -> CellularData.Disconnected
        TelephonyManager.DATA_CONNECTING -> CellularData.Connecting
        TelephonyManager.DATA_CONNECTED -> CellularData.Connected
        TelephonyManager.DATA_SUSPENDED -> CellularData.Suspended
        else -> CellularData.NotReported
    }

/**
 * The radio technology, folded onto the generations a walk reads. The families collapse on purpose —
 * HSPA+ and UMTS behave the same for a handoff, and the distinction that matters to a QUIC path is
 * which generation carried it. Anything unmapped keeps the platform's own constant name.
 *
 * The CDMA constants are deprecated in the SDK because the networks are switched off; a phone that
 * still reports one must still be recorded as CDMA rather than as an unnamed number.
 */
@Suppress("DEPRECATION")
private fun bearerOf(raw: Int): CellularBearer =
    when (raw) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> CellularBearer.None
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GSM,
        -> CellularBearer.Gsm
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        -> CellularBearer.Umts
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        -> CellularBearer.Cdma
        TelephonyManager.NETWORK_TYPE_LTE -> CellularBearer.Lte
        TelephonyManager.NETWORK_TYPE_NR -> CellularBearer.Nr
        else -> CellularBearer.Other("NETWORK_TYPE_$raw")
    }
