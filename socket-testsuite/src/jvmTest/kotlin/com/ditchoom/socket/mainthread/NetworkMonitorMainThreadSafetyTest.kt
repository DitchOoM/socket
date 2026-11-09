package com.ditchoom.socket.mainthread

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.default
import com.ditchoom.socket.enumerateNetworkInterfaces
import com.ditchoom.socket.listNetworkInterfaces
import com.ditchoom.socket.open
import com.ditchoom.socket.processDefault
import com.ditchoom.socket.shared
import kotlin.test.Test
import kotlin.test.assertSame

/*
 * The network-monitor entry points from the main-like thread, each class in a fresh JVM so it is the
 * first use of what it drives (the JDK 21+ desktop monitor's FFM routing socket and libc lookup, the
 * interface table). Each suspend replacement has a control class beside it showing that the deprecated
 * blocking function it replaces IS caught, so a pass is not just the harness failing to see the work.
 */

/** [listNetworkInterfaces] on the main-like thread. */
class NetworkInterfacesMainThreadSafetyTest {
    @Test
    fun listNetworkInterfacesDoesNotBlock() =
        runOnMainLike {
            listNetworkInterfaces()
            assertOnMainLike("listNetworkInterfaces")
        }
}

/** Control: the deprecated [enumerateNetworkInterfaces] blocks the main-like thread. */
class DeprecatedEnumerateNetworkInterfacesMainThreadSafetyControlTest {
    @Test
    fun enumerateNetworkInterfacesOnTheMainLikeThreadIsCaught() =
        assertBlocksTheMainLikeThread {
            @Suppress("DEPRECATION")
            enumerateNetworkInterfaces()
        }
}

/** [NetworkMonitor.open], a read of its state, and [NetworkMonitor.close], on the main-like thread. */
class NetworkMonitorOpenMainThreadSafetyTest {
    @Test
    fun openReadAndCloseDoNotBlock() =
        runOnMainLike {
            val monitor = NetworkMonitor.open()
            assertOnMainLike("NetworkMonitor.open")
            monitor.state.value
            monitor.close()
        }
}

/** Control: the deprecated [NetworkMonitor.Companion.default] blocks the main-like thread. */
class DeprecatedNetworkMonitorDefaultMainThreadSafetyControlTest {
    @Test
    fun defaultOnTheMainLikeThreadIsCaught() =
        assertBlocksTheMainLikeThread {
            @Suppress("DEPRECATION")
            NetworkMonitor.default().close()
        }
}

/** [NetworkMonitor.shared] on the main-like thread: the first call builds it, the second reuses it. */
class NetworkMonitorSharedMainThreadSafetyTest {
    @Test
    fun sharedDoesNotBlock() =
        runOnMainLike {
            val first = NetworkMonitor.shared()
            assertOnMainLike("NetworkMonitor.shared")
            assertSame(first, NetworkMonitor.shared(), "shared() must hand out one monitor")
            assertOnMainLike("NetworkMonitor.shared, second call")
        }
}

/** Control: the deprecated [NetworkMonitor.Companion.processDefault] blocks the main-like thread. */
class DeprecatedProcessDefaultMainThreadSafetyControlTest {
    @Test
    fun processDefaultOnTheMainLikeThreadIsCaught() =
        assertBlocksTheMainLikeThread {
            @Suppress("DEPRECATION")
            NetworkMonitor.processDefault()
        }
}
