package com.ditchoom.socket.quic.core.testing

import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/**
 * [FlightEvidenceListener] for runners that report through JUnit 4's [RunListener] (Android instrumentation, which finds
 * it by the `listener` instrumentation argument): a failing test writes its connections' flight recordings and names the
 * files on its error stream.
 */
class FlightEvidenceRunListener : RunListener() {
    override fun testStarted(description: Description) = FailureEvidence.begin()

    override fun testFailure(failure: Failure) {
        val written = FailureEvidence.failed("${failure.description.testClass.simpleName}.${failure.description.methodName}")
        if (written !is EvidenceWritten.NoConnections) System.err.println(FailureEvidence.message(written, "test failed"))
    }

    /** After a failure the recordings are already written and nothing is kept, so this releases nothing. */
    override fun testFinished(description: Description) = FailureEvidence.passed()
}
