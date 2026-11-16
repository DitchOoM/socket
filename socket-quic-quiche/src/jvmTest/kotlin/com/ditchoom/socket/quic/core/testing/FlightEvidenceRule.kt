package com.ditchoom.socket.quic.core.testing

import org.junit.internal.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A core test's failure writes the flight recording of every connection the test touched ([FailureEvidence]) and names
 * the files in the failure. A passing test writes nothing.
 */
class FlightEvidenceRule : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                FailureEvidence.begin()
                try {
                    base.evaluate()
                    FailureEvidence.passed()
                } catch (skipped: AssumptionViolatedException) {
                    FailureEvidence.passed()
                    throw skipped
                } catch (failure: Throwable) {
                    val written = FailureEvidence.failed("${description.testClass.simpleName}.${description.methodName}")
                    throw AssertionError(FailureEvidence.message(written, failure.message), failure)
                }
            }
        }
}
