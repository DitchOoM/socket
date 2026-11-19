package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.AppleTestCerts
import com.ditchoom.socket.quic.QuicTlsConfig

/** The committed test identity, materialized into `TMPDIR` where the simulator's working directory has no `testcerts/`. */
internal actual fun nativeCoreTls(): QuicTlsConfig = AppleTestCerts.tlsConfig
