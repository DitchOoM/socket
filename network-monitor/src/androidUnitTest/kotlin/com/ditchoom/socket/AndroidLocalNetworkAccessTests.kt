package com.ditchoom.socket

import android.os.Build
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class AndroidLocalNetworkAccessTests {
    @Before
    @After
    fun clearCapturedContext() {
        NetworkMonitor.resetAndroidContextForTesting()
    }

    @Test
    fun withoutACapturedContextTheAccessIsUnknown() {
        assertSame(AndroidLocalNetworkAccess.Unknown, NetworkMonitor.androidLocalNetworkAccess())
    }

    @Test
    fun aSystemOlderThanTheRestrictionAllowsTheLocalNetwork() {
        NetworkMonitor.installAndroidApplicationContext(RuntimeEnvironment.getApplication())
        assertSame(AndroidLocalNetworkAccess.Allowed, NetworkMonitor.androidLocalNetworkAccess())
    }
}
