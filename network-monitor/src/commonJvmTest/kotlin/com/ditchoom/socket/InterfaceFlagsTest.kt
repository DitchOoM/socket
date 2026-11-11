package com.ditchoom.socket

import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InterfaceFlagsTest {
    @Test
    fun aVanishedInterfaceHasNoFlagSet() {
        assertEquals(false, flagOrGone { throw SocketException("No such device (getFlags() failed)") })
    }

    @Test
    fun aFlagThatIsSetIsReported() {
        assertTrue(flagOrGone { true })
    }

    @Test
    fun onlyAVanishedInterfaceIsAbsorbed() {
        assertFailsWith<IllegalStateException> { flagOrGone { error("a bug") } }
    }

    @Test
    fun theHostsRealInterfacesAreEnumerableWithoutThrowing() {
        platformNetworkInterfaces()
        resolveJvmNetworkState()
        currentPrimaryNetworkId()
    }
}
