package com.ditchoom.socket.quic.core.sched

/**
 * Which threads send a socket's datagrams. A caller enters a connection only with a lane of the socket's
 * [CallerLanes], so the rule is the lanes the socket is built with: [ByLoop] builds none.
 */
internal sealed interface SendRule {
    /** Only the loop serves connections and sends: every caller's work is handed to it. */
    data object ByLoop : SendRule

    /** A caller that finds a connection free serves it and sends its output from a lane of its own. */
    data object InPlace : SendRule
}

/** The kernel under a runtime's sockets, which decides its [SendRule]. */
internal enum class HostKernel {
    /** macOS, iOS, tvOS, watchOS: concurrent senders on one UDP socket contend in the kernel. */
    Darwin,
    Linux,
    Windows,
    Other,
}

/** The [SendRule] for sockets on [kernel]. */
internal fun sendRuleOf(kernel: HostKernel): SendRule =
    when (kernel) {
        HostKernel.Darwin -> SendRule.ByLoop
        HostKernel.Linux, HostKernel.Windows, HostKernel.Other -> SendRule.InPlace
    }

/** The kernel this runtime runs on. */
internal expect val kernelOfThisRuntime: HostKernel

/** This runtime's [SendRule], chosen once per process. */
internal val sendRuleOfThisRuntime: SendRule = sendRuleOf(kernelOfThisRuntime)

/** How many lanes a socket under this rule lends out of [wanted]: none for [SendRule.ByLoop]. See [callerLanesOf]. */
internal fun SendRule.laneCount(wanted: Int): Int =
    when (this) {
        is SendRule.ByLoop -> 0
        is SendRule.InPlace -> wanted
    }
