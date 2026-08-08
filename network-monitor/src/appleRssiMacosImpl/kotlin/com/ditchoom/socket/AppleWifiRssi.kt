package com.ditchoom.socket

import platform.CoreWLAN.CWWiFiClient

/**
 * macOS is the one Apple platform whose **public** API reports Wi-Fi RSSI: CoreWLAN's
 * `CWWiFiClient`. This file is compiled into the macOS targets' main compilations only (see the
 * `appleRssiMacosImpl` srcDir wiring in build.gradle.kts); every other Apple target compiles the
 * honest-absence twin in `appleRssiDefaultImpl` instead — same signatures, no measurement.
 */
internal val appleLinkQualityResolution: LinkQualityResolution = LinkQualityResolution.Rssi

/**
 * The current default Wi-Fi interface's RSSI in dBm, or `null` when there is no measurement to
 * report: no Wi-Fi interface, not associated, or the OS declining to answer (newer macOS gates
 * CoreWLAN details behind Location permission for unentitled processes). CoreWLAN reports those
 * cases as an interface whose `rssiValue` is 0 — and 0 dBm is not a value a real association
 * produces — so 0 maps to `null`, never to a reading.
 *
 * A synchronous, cheap read (no scan is triggered); called once per `NWPathMonitor` update while the
 * primary interface is Wi-Fi.
 */
internal fun appleWifiRssiOrNull(): Int? {
    val rssi = CWWiFiClient.sharedWiFiClient().`interface`()?.rssiValue() ?: return null
    return if (rssi == 0L) null else rssi.toInt()
}
