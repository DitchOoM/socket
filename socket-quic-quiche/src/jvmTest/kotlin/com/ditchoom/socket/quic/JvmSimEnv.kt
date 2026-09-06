package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.hostOsSockAddrLayout

/**
 * The JVM's [MigrationSimEnv]: the loaded `libquiche` binding (JNI or FFM, per
 * `-PquicheJvmBackend`), the certificate fixtures off the test classpath, and the running host OS's
 * `sockaddr` layout.
 *
 * Shared by every JVM sim test so the classpath probe lives in one place — the Kotlin/Native members
 * find their fixtures by filesystem instead, which is the whole reason this is not in the sim itself.
 */
internal fun jvmMigrationSimEnv(): MigrationSimEnv =
    MigrationSimEnv(
        api = loadQuicheApi(),
        certChainPath = jvmSimCertPath("cert.crt"),
        privKeyPath = jvmSimCertPath("cert.key"),
        codec = SocketAddressCodec(hostOsSockAddrLayout()),
    )

private fun jvmSimCertPath(name: String): String {
    val url =
        MigrationSimEnv::class.java.classLoader.getResource("certs/$name")
            ?: error("Test cert not found: certs/$name")
    return java.io.File(url.toURI()).absolutePath
}
