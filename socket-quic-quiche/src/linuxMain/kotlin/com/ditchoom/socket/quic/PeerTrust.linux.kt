package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer

/** BoringSSL checks the chain in the handshake, against the pinned anchors or the system bundle. */
internal actual fun platformChainTrust(options: QuicOptions): PlatformChainTrust = PlatformChainTrust.InHandshake

internal actual fun parsePinnedLeafFieldsHere(der: ReadBuffer): X509PinFields? = parsePinnedLeafFieldsLinux(der)
