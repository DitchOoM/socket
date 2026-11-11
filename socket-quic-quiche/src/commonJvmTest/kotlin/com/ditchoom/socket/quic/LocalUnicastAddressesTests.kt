@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalUnicastAddressesTests {
    private class Fake(
        private val up: Boolean?,
        host: String,
    ) : LocalInterface {
        private val address = InetAddress.getByName(host)
        override val isUp: Boolean get() = up ?: throw SocketException("No such device (getFlags() failed)")
        override val addresses: List<InetAddress> get() = listOf(address)
    }

    private val vanished = Fake(null, "10.9.9.9")

    @Test
    fun aVanishedInterfaceIsSkippedAndTheRestAreReturned() {
        val found =
            enumerateLocalUnicastAddresses {
                sequenceOf(Fake(true, "10.0.0.1"), vanished, Fake(false, "10.0.0.3"), Fake(true, "10.0.0.4"))
            }
        assertEquals(listOf("10.0.0.1", "10.0.0.4"), found)
    }

    @Test
    fun whenEveryInterfaceVanishedThereAreNoAddresses() {
        assertEquals(emptyList(), enumerateLocalUnicastAddresses { sequenceOf(vanished, vanished) })
    }

    @Test
    fun aBindWithNoEnumerableAddressKeepsTheWildcard() =
        runBlocking {
            val pool = QuicheDriver.newRecvBufPool(BufferFactory.deterministic())
            val served =
                QuicPortBinding.Own(port = 0, host = null).openServerChannel(
                    pool,
                    socketPerLocalAddress(Dispatchers.IO) { enumerateLocalUnicastAddresses { sequenceOf(vanished) } },
                )
            try {
                assertEquals(ReplySourcePinning.PlatformChooses, served.pinning)
            } finally {
                served.channel.close()
            }
        }
}
