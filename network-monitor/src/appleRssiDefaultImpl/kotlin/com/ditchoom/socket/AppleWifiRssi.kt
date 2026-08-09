package com.ditchoom.socket

/**
 * The honest-absence twin of `appleRssiMacosImpl` for iOS/tvOS/watchOS, whose **public** API exposes
 * no Wi-Fi RSSI (the private facilities that do are not App Store-safe). Declaring
 * [LinkQualityResolution.None] is the whole feature on these targets: a consumer learns at
 * configuration time that quality trend data does not exist here, instead of watching a
 * [LinkQuality.Unavailable] that never moves — and nothing fabricates a number.
 */
internal val appleLinkQualityResolution: LinkQualityResolution = LinkQualityResolution.None

/** No public RSSI source on this platform — there is never a measurement to report. */
internal fun appleWifiRssiOrNull(): Int? = null
