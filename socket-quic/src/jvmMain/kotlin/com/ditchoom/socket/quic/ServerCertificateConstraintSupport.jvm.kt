package com.ditchoom.socket.quic

/** JVM enforces the full W3C constraints via the `java.security` X.509 parser (see the quiche backend). */
actual val serverCertificateConstraintSupport: ServerCertificateConstraintSupport
    get() = ServerCertificateConstraintSupport.Enforced
