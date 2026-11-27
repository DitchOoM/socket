package com.ditchoom.socket.quic

import android.app.ActivityManager
import android.content.Context

/**
 * The Java heap this test process was given, against what the device grants a largeHeap app.
 *
 * The manifest asks for largeHeap because the many-connection suites need it. A process forked from a
 * zygote that started without `dalvik.vm.heapsize` has the runtime's 16 MB fallback whatever getprop says
 * now, and those suites then fail with OutOfMemoryError far from the cause.
 */
sealed interface TestProcessHeap {
    val maxBytes: Long

    data class Granted(
        override val maxBytes: Long,
    ) : TestProcessHeap

    data class Clamped(
        override val maxBytes: Long,
        val largeHeapBytes: Long,
    ) : TestProcessHeap {
        val explanation: String
            get() =
                "this test process has a ${maxBytes / MB} MB Java heap, but the device grants a largeHeap app " +
                    "${largeHeapBytes / MB} MB (dalvik.vm.heapsize). An app's heap is the zygote's -Xmx, fixed when " +
                    "the zygote starts: this zygote started before dalvik.vm.heapsize was set (the 16 MB fallback), " +
                    "or the device is low-RAM and ignores largeHeap. The many-connection suites cannot fit, so " +
                    "no other test ran. On an emulator, restart the framework (adb root; adb shell stop; adb shell " +
                    "start) and run again; scripts/android-zygote-heap.sh does this on CI."
    }

    companion object {
        private const val MB = 1024L * 1024L

        fun of(context: Context): TestProcessHeap {
            val maxBytes = Runtime.getRuntime().maxMemory()
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val largeHeapBytes = activityManager.largeMemoryClass * MB
            return if (maxBytes < largeHeapBytes) Clamped(maxBytes, largeHeapBytes) else Granted(maxBytes)
        }
    }
}
