package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.PlatformChainTrust
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.X509PinFields
import com.ditchoom.socket.quic.parsePinnedLeafFieldsJvm

/** BoringSSL checks the chain in the handshake, against the pinned anchors or the JVM's defaults. */
internal actual fun platformChainTrust(options: QuicOptions): PlatformChainTrust = PlatformChainTrust.InHandshake

internal actual fun parsePinnedLeafFieldsHere(der: ReadBuffer): X509PinFields? = parsePinnedLeafFieldsJvm(der)
