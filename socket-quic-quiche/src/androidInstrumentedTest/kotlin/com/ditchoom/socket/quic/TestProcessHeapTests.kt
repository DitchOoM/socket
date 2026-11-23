package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestProcessHeapTests {
    @Test
    fun theTestProcessHasTheHeapTheDeviceGrantsALargeHeapApp() {
        when (val heap = TestProcessHeap.of(InstrumentationRegistry.getInstrumentation().targetContext)) {
            is TestProcessHeap.Granted -> {}
            is TestProcessHeap.Clamped -> fail(heap.explanation)
        }
    }
}
