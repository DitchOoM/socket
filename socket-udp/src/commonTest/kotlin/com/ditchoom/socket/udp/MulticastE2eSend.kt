package com.ditchoom.socket.udp

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/**
 * Put [datagram] on the wire for a multicast end-to-end leg, telling "this host will not carry it"
 * apart from a real defect. Returns `null` when the send was accepted, or the reason to skip on.
 *
 * The multicast e2e legs are deliberately conditional: kernel-routed delivery is environment-gated
 * (macOS Local Network Privacy denies an unattended `test.kexe`, a container may have no multicast
 * route or no IPv6 interface at all), so they assert exact bytes when a datagram arrives and log a loud
 * skip when none does. That ladder already treats a refused **join** as unavailability. It did not
 * treat a refused **send** as unavailability, for the simple reason that a send could not refuse: four
 * of five backends discarded their send result, so an unroutable host produced a clean return, no
 * delivery, and the intended skip.
 *
 * Now that a send reports, the same environmental condition arrives as a `DatagramSendException` and
 * must be recognized in the same place — this is the module's own first encounter with the breaking
 * change, and the same shape flagged to downstream ICE in DitchOoM/webrtc#143. Observed in CI as
 * `OsError(errno=99)` — `EADDRNOTAVAIL`, the source address not being assignable for a group on that
 * interface — on `iosSimulatorArm64` and in a container with no IPv6 interface.
 *
 * [DatagramSendError.TooLarge] and [DatagramSendError.WouldBlock] deliberately still throw: these
 * payloads are a dozen bytes onto an idle socket, so either one would be a defect in this module's size
 * accounting or backpressure handling rather than a fact about the host. Everything else is the OS
 * declining, including [DatagramSendError.Transport] — which on the JVM is the residue `jvmSendErrorOf`
 * could not classify (#457 narrowed the rest to real members, but NIO surfaces no errno, so an
 * unrecognized refusal stays raw) — and treating any of them as fatal would make every host without
 * routable multicast fail a test that is explicitly conditional on having one.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun MulticastDatagramChannel.sendForMulticastE2e(
    datagram: ReadBuffer,
    to: SocketAddress,
): String? =
    try {
        send(datagram, to = to)
        null
    } catch (e: DatagramSendException) {
        when (val error = e.error) {
            is DatagramSendError.Unreachable,
            is DatagramSendError.PortUnreachable,
            is DatagramSendError.NotPermitted,
            is DatagramSendError.OsError,
            is DatagramSendError.PlatformError,
            is DatagramSendError.Transport,
            -> "the OS refused the send — ${error.describe()}"
            is DatagramSendError.TooLarge,
            DatagramSendError.WouldBlock,
            -> throw e
        }
    }
