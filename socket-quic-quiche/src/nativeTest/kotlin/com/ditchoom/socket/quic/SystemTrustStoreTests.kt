package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The system trust store a client with no pinned CAs verifies against is chosen once, from what the filesystem holds. */
class SystemTrustStoreTests {
    private val bundles = listOf("/a/bundle.crt", "/b/bundle.crt")
    private val directories = listOf("/a/certs", "/b/certs")

    private fun choose(vararg present: String): SystemTrustStore {
        val held = present.toSet()
        return systemTrustStore({ it in held }, bundles, directories)
    }

    @Test
    fun theFirstBundleFilePresentIsChosen() {
        val store = assertIs<SystemTrustStore.BundleFile>(choose("/b/bundle.crt", "/a/bundle.crt"))
        assertEquals("/a/bundle.crt", store.path)
    }

    @Test
    fun aBundleFileWinsOverADirectory() {
        assertIs<SystemTrustStore.BundleFile>(choose("/a/certs", "/b/bundle.crt"))
    }

    @Test
    fun withNoBundleFileTheFirstHashedDirectoryPresentIsChosen() {
        val store = assertIs<SystemTrustStore.HashedDirectory>(choose("/b/certs", "/a/certs"))
        assertEquals("/a/certs", store.path)
    }

    @Test
    fun withNeitherTheStoreIsNoneAndNamesWhatWasSearched() {
        val store = assertIs<SystemTrustStore.None>(choose())
        assertEquals(bundles + directories, store.searched)
    }
}
