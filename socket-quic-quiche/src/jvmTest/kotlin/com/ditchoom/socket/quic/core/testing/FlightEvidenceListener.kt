package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.core.conn.MismatchResponse
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

/**
 * Every JVM test's connections are recorded, and a failing test writes their flight recordings ([FailureEvidence]) and
 * names the files on its error stream. The JUnit Platform finds it by `ServiceLoader`
 * (`META-INF/services/org.junit.platform.launcher.TestExecutionListener`), so no test class opts in. A passing or
 * skipped test writes nothing.
 */
class FlightEvidenceListener : TestExecutionListener {
    /** A datagram due on a clock the loop does not read ends its connection, so the test that caused it fails. */
    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        MismatchResponse.failTheTest()
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        if (testIdentifier.isTest) FailureEvidence.begin()
    }

    override fun executionFinished(
        testIdentifier: TestIdentifier,
        testExecutionResult: TestExecutionResult,
    ) {
        if (!testIdentifier.isTest) return
        when (testExecutionResult.status) {
            TestExecutionResult.Status.SUCCESSFUL, TestExecutionResult.Status.ABORTED -> FailureEvidence.passed()
            TestExecutionResult.Status.FAILED -> {
                val written = FailureEvidence.failed(nameOf(testIdentifier))
                if (written !is EvidenceWritten.NoConnections) System.err.println(FailureEvidence.message(written, "test failed"))
            }
        }
    }

    /** `Class.method`, which [FailureEvidence.failed] splits into the directory and the file prefix. */
    private fun nameOf(test: TestIdentifier): String =
        when (val source = test.source.orElse(null)) {
            is MethodSource -> "${source.className.substringAfterLast('.')}.${source.methodName}"
            else -> test.displayName
        }
}
