package com.ditchoom.socket.quic

/**
 * On the web the browser's own `WebTransport` stack enforces the W3C `serverCertificateHashes` constraints
 * natively (socket-quic has no JS QUIC backend of its own), so the platform reports them as enforced.
 */
actual val serverCertificateConstraintSupport: ServerCertificateConstraintSupport
    get() = ServerCertificateConstraintSupport.Enforced
