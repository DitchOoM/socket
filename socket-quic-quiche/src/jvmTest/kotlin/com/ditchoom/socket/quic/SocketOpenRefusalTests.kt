package com.ditchoom.socket.quic

import java.net.BindException
import java.net.NoRouteToHostException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A socket the JDK opens (the core's stack on Windows) refuses a bind or a route with a `SocketException`, not qnet's
 * [NativeSocketRefusal]: an entry point answers both as a value (CI Windows JVM: a serve on a taken port escaped as
 * `BindException: Address already in use: bind`).
 */
class SocketOpenRefusalTests {
    @Test
    fun theJdksSocketRefusalsAreRefusalsAndOtherFailuresAreNot() {
        assertEquals<SocketOpenRefusal>(SocketOpenRefusal.Refused, socketOpenRefusalOf(BindException()))
        assertEquals<SocketOpenRefusal>(SocketOpenRefusal.Refused, socketOpenRefusalOf(NoRouteToHostException()))
        assertEquals<SocketOpenRefusal>(SocketOpenRefusal.Refused, socketOpenRefusalOf(NativeSocketRefusal(SocketRefusal.EAddrInUse)))
        assertEquals<SocketOpenRefusal>(SocketOpenRefusal.NotARefusal, socketOpenRefusalOf(IllegalStateException()))
    }
}
