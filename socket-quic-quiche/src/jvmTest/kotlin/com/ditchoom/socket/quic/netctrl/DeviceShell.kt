package com.ditchoom.socket.quic.netctrl

/**
 * What running one privileged command on the device produced.
 *
 * Sealed rather than a `Boolean` or a bare exit code because the two states carry different
 * obligations: [Ran] lets [NetworkControlServer] tell a client its impairment applied, and [Failed]
 * has to carry enough to say *why* it did not — the previous code kept only a `println` and answered
 * `Ok` either way (#389).
 */
sealed interface ShellOutcome {
    /** Exit status 0. [output] is whatever the command printed, already trimmed. */
    data class Ran(
        val output: String,
    ) : ShellOutcome

    /** Non-zero exit, or the process could not be started at all. */
    data class Failed(
        val exitCode: Int,
        val output: String,
    ) : ShellOutcome
}

/**
 * How [NetworkControlServer] runs a privileged command on the attached device.
 *
 * A seam, and the reason is the defect it is being introduced for. Every state worth testing here is
 * one `adb` cannot be asked to produce on demand — a device without root, a wrong serial, `adb` not
 * on the PATH — so the decision that mattered ("did that impairment actually apply?") had no test
 * that could reach it. #389 is what that costs: on a physical SM-F956U1 all five
 * `AndroidQuicMigrationTests` cases passed with no UDP blocked, no latency added and airplane mode
 * never toggled, because `su: inaccessible or not found` was logged as non-fatal and answered `Ok`.
 */
fun interface DeviceShell {
    /** Run [shellCommand] as root on the device. */
    fun run(shellCommand: String): ShellOutcome

    companion object {
        /**
         * The real thing: `adb [-s serial] shell su 0 <command>`.
         *
         * `su 0` and not `su -c`: the emulator images use toybox's `su`, whose first argument is the
         * uid. A failure to even *start* adb is [ShellOutcome.Failed] with [PROCESS_NOT_STARTED]
         * rather than a thrown exception, so "adb is missing" reaches a client as an answer instead
         * of killing the connection — which is the same distinction the rest of this change is about.
         */
        fun adb(adbSerial: String?): DeviceShell =
            DeviceShell { shellCommand ->
                val command =
                    buildList {
                        add("adb")
                        if (adbSerial != null) {
                            add("-s")
                            add(adbSerial)
                        }
                        add("shell")
                        add("su")
                        add("0")
                        add(shellCommand)
                    }
                try {
                    val process =
                        ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start()
                    val output =
                        process.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                    val exitCode = process.waitFor()
                    if (exitCode == 0) ShellOutcome.Ran(output) else ShellOutcome.Failed(exitCode, output)
                } catch (e: Exception) {
                    ShellOutcome.Failed(PROCESS_NOT_STARTED, "could not run `${command.joinToString(" ")}`: $e")
                }
            }

        /**
         * Exit code for "the process never started", chosen outside the 0..255 an exited process can
         * report so it cannot be confused with one the device produced.
         */
        const val PROCESS_NOT_STARTED: Int = -1
    }
}
