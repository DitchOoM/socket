package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.PlatformChainTrust
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.X509PinFields
import com.ditchoom.socket.quic.appleSystemChainTrust
import com.ditchoom.socket.quic.parsePinnedLeafFieldsDer
import com.ditchoom.socket.quic.usesSecTrust

/** SecTrust after the handshake where the 4.x engine uses it (the iOS family's system store); BoringSSL otherwise. */
internal actual fun platformChainTrust(options: QuicOptions): PlatformChainTrust =
    if (usesSecTrust(options)) appleSystemChainTrust else PlatformChainTrust.InHandshake

/** Apple has no native X.509 reader usable on every supported OS version; the shared DER walk reads the fields. */
internal actual fun parsePinnedLeafFieldsHere(der: ReadBuffer): X509PinFields? = parsePinnedLeafFieldsDer(der)
