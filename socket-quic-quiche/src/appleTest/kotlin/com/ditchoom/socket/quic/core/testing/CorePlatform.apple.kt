package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.AppleTestCerts
import com.ditchoom.socket.quic.PeerCertificateSupport
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.SecurityFrameworkPeerCertificateBackend

/** The committed test identity, materialized into `TMPDIR` where the simulator's working directory has no `testcerts/`. */
internal actual fun nativeCoreTls(): QuicTlsConfig = AppleTestCerts.tlsConfig

internal actual fun platformPeerCertificates(): PeerCertificateSupport =
    PeerCertificateSupport.Available(SecurityFrameworkPeerCertificateBackend)
