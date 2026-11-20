package com.ditchoom.socket.quic

/** Where a host keeps the CA certificates a client with no pinned CAs verifies against. */
internal sealed interface SystemTrustStore {
    /** One PEM file holding every CA. */
    class BundleFile(
        val path: String,
    ) : SystemTrustStore

    /** A hashed directory (`c_rehash` layout), one file per CA. */
    class HashedDirectory(
        val path: String,
    ) : SystemTrustStore

    /** The host holds neither; [searched] are the paths looked at. */
    class None(
        val searched: List<String>,
    ) : SystemTrustStore
}

/** Whether a path exists on the host. */
internal fun interface PathProbe {
    fun exists(path: String): Boolean
}

/** The first of [bundles] [probe] finds, else the first of [directories], else [SystemTrustStore.None]. */
internal fun systemTrustStore(
    probe: PathProbe,
    bundles: List<String>,
    directories: List<String>,
): SystemTrustStore {
    bundles.firstOrNull(probe::exists)?.let { return SystemTrustStore.BundleFile(it) }
    directories.firstOrNull(probe::exists)?.let { return SystemTrustStore.HashedDirectory(it) }
    return SystemTrustStore.None(bundles + directories)
}
