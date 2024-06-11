@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.NetworkCapabilities.FULL_SOCKET_ACCESS
import com.ditchoom.socket.NetworkCapabilities.WEBSOCKETS_ONLY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

internal fun runTestNoTimeSkipping(count: Int = 1, block: suspend TestScope.() -> Unit) = runTest {
    try {
        withContext(Dispatchers.Default.limitedParallelism(count)) {
            block()
        }
    } catch (e: UnsupportedOperationException) {
        // ignore
        when (getNetworkCapabilities()) {
            FULL_SOCKET_ACCESS -> throw e
            WEBSOCKETS_ONLY -> {} // ignore, expected on browsers
        }
    }
}

