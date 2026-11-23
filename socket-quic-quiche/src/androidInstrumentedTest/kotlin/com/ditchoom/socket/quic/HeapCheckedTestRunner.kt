package com.ditchoom.socket.quic

import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/**
 * Reports the test process's heap before any test runs. When the heap is [TestProcessHeap.Clamped], only
 * [TestProcessHeapTests] runs, so the run fails once with the reason instead of with OutOfMemoryErrors and a
 * process crash in whichever suites happen to allocate most.
 */
class HeapCheckedTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        val heap = TestProcessHeap.of(targetContext)
        Log.i(TAG, "test process heap: $heap")
        when (heap) {
            is TestProcessHeap.Granted -> {}
            is TestProcessHeap.Clamped -> {
                Log.e(TAG, heap.explanation)
                SELECTION_ARGUMENTS.forEach(arguments::remove)
                arguments.putString("class", TestProcessHeapTests::class.java.name)
            }
        }
        super.onCreate(arguments)
    }

    private companion object {
        const val TAG = "TestHeap"

        /** The instrumentation arguments that choose which tests run. */
        val SELECTION_ARGUMENTS =
            listOf("class", "notClass", "package", "notPackage", "annotation", "notAnnotation", "size", "testFile", "notTestFile")
    }
}
