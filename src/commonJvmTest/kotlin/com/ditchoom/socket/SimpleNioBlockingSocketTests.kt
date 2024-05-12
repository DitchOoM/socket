package com.ditchoom.socket

import org.junit.Test

class SimpleNioBlockingSocketTests {
    init {
        useAsyncChannels = false
        useNioBlocking = true
    }

    @Test
    fun connectTimeoutWorks() = SimpleSocketTests().connectTimeoutWorks()

    @Test
    fun invalidHost() = SimpleSocketTests().invalidHost()

    @Test
    fun closeWorks() = SimpleSocketTests().closeWorks()

    @Test
    fun httpRawSocketExampleDomain() = SimpleSocketTests().httpRawSocketExampleDomain()

    @Test
    fun httpsRawSocketExampleDomain() = SimpleSocketTests().httpsRawSocketExampleDomain()

    @Test
    fun httpRawSocketGoogleDomain() = SimpleSocketTests().httpRawSocketGoogleDomain()

    @Test
    fun httpsRawSocketGoogleDomain() = SimpleSocketTests().httpsRawSocketGoogleDomain()

    @Test
    fun manyClientsConnectingToOneServer() = SimpleSocketTests().manyClientsConnectingToOneServer()

    @Test
    fun serverEcho() = SimpleSocketTests().serverEcho()

    @Test
    fun clientEcho() = SimpleSocketTests().clientEcho()

    @Test
    fun suspendingInputStream() = SimpleSocketTests().suspendingInputStream()
}
