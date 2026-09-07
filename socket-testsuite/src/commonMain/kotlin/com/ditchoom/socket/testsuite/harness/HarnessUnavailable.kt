package com.ditchoom.socket.testsuite.harness

/**
 * A harness facility a test asked for is not on this runtime, or did not answer.
 *
 * Carries an enumerated [reason] rather than only a sentence, so a caller — or a failure report — can
 * tell "this harness build predates the facility" from "the sidecar refused the request". The rendered
 * message exists for a human reading a stack trace; [reason] and [detail] are the payload.
 */
class HarnessUnavailable(
    val reason: Reason,
    val detail: String,
) : IllegalStateException("$reason: $detail") {
    enum class Reason {
        /**
         * The controller's `/describe` does not list the scenario. Usually a harness image older than
         * the facility, which is why absence is per-scenario: a missing QUIC relay must not make an
         * unrelated UDP suite unavailable too.
         */
        ScenarioNotInManifest,

        /** The sidecar answered, but not with success — its status is in [detail]. */
        SidecarRejectedRequest,
    }
}
