package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.EcnPreference
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EINVAL
import platform.posix.ENODEV
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Linux row of the §7.1 control-plane matrix, asserted against real io_uring sockets: capability
 * advertisement plus positive read-side roundtrips (ECN / TTL / IP_PKTINFO destination address). Kept
 * out of the shared `nativeTest` suite because Apple advertises a different (managed) ceiling —
 * capabilities are platform-specific by design (consumers query, never assume).
 */
@OptIn(ExperimentalDatagramApi::class)
class LinuxUdpControlPlaneTests {
    private val opened = mutableListOf<DatagramChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private suspend fun bind(host: String = "127.0.0.1"): AddressedDatagramChannel = UdpSocket.bind(host, 0).also { opened.add(it) }

    private suspend fun connectTo(peer: SocketAddress): ConnectedDatagramChannel =
        UdpSocket.connect(peer.host, peer.port).also { opened.add(it) }

    private fun payload(text: String): PlatformBuffer {
        val bytes = text.encodeToByteArray()
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        buf.writeBytes(bytes)
        buf.resetForRead()
        return buf
    }

    private suspend fun DatagramChannel.recvDatagram() = assertIs<DatagramReadResult.Received>(withTimeout(5_000) { receive() }).datagram

    private fun udpTest(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { body() } }

    @Test
    fun capabilitiesMatchTheRichLinuxCeiling() =
        udpTest {
            val caps = bind().capabilities
            // Full send + receive control plane on Linux native.
            assertTrue(caps.ecnSend)
            assertTrue(caps.ecnReceive)
            assertTrue(caps.dscpSend)
            assertTrue(caps.dontFragment)
            assertTrue(caps.hopLimitSend)
            assertTrue(caps.hopLimitReceive)
            assertTrue(caps.localAddressReceive)
            // Send-side IP_PKTINFO too, since #556 step 2 — and the assertion is only worth making
            // because [fromLocalPinsTheSourceTheReceiverSees] proves the flag is not just a claim.
            assertTrue(caps.sourceAddressSelect)
            // Multicast is the one absent capability; MulticastIoUringDatagramChannel flips it on.
            assertFalse(caps.multicast)
        }

    @Test
    fun ecnCodepointRoundTrips() =
        udpTest {
            val a = bind()
            val b = bind()
            // b stamps ECT(0) via IP_TOS; loopback preserves the TOS octet, and a's IP_RECVTOS reports it.
            b.send(payload("ecn"), to = a.localAddress, options = DatagramSendOptions(ecn = EcnPreference.Ect0))
            assertEquals(Ecn.Ect0, a.recvDatagram().ecn)
        }

    @Test
    fun hopLimitRoundTrips() =
        udpTest {
            val a = bind()
            val b = bind()
            // Loopback does not decrement TTL, so the received hop limit equals what b set.
            b.send(payload("ttl"), to = a.localAddress, options = DatagramSendOptions(hopLimit = 7))
            assertEquals(7, a.recvDatagram().hopLimit.value)
        }

    @Test
    fun localAddressIsReportedViaPktinfo() =
        udpTest {
            val a = bind()
            val b = bind()
            b.send(payload("dst"), to = a.localAddress)
            val d = a.recvDatagram()
            // IP_PKTINFO reports the datagram's destination IP — the loopback address a is bound to.
            assertEquals("127.0.0.1", d.localAddress.orNull()?.host)
        }

    // ---- send-side source selection (#556 step 2) ----

    /**
     * The heart of it: a wildcard-bound sender leaves from the address `fromLocal` names.
     *
     * Asserted **from the receiver**, on `Datagram.peer` — the source the kernel actually put in the
     * IP header, decoded by a different socket from a different `recvmsg`. Nothing here consults the
     * encoder under test, so a cmsg that is built wrong, dropped, or ignored cannot agree with itself
     * into a pass.
     *
     * The unpinned control send is what makes the pinned assertion mean anything. Linux hands the
     * whole of `127.0.0.0/8` to `lo`, so the kernel's own choice for a `127.0.0.1` destination is
     * `127.0.0.1`: the two sends differ in exactly one option and must produce two different sources.
     * Without the control, an implementation that pinned nothing at all would still have to be caught
     * by luck.
     */
    @Test
    fun fromLocalPinsTheSourceTheReceiverSees() =
        udpTest {
            val receiver = bind()
            // The #556 shape: bound to the wildcard, so the source is the kernel's to choose until
            // something takes the choice away.
            val sender = bind(host = "0.0.0.0")

            sender.send(payload("unpinned"), to = receiver.localAddress)
            assertEquals(
                "127.0.0.1",
                receiver.recvDatagram().peer.host,
                "the kernel's own choice for a 127.0.0.1 destination — the control this test is measured against",
            )

            sender.send(
                payload("pinned"),
                to = receiver.localAddress,
                options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("127.0.0.2", 0)),
            )
            assertEquals(
                "127.0.0.2",
                receiver.recvDatagram().peer.host,
                "IP_PKTINFO must move the source off the kernel's preference, or the reply a client " +
                    "dialled on another local address is still dropped as off-path",
            )
        }

    /**
     * A source in the other address family is refused *here*, before the syscall, because the kernel
     * will not refuse it: an `IPV6_PKTINFO` control message on an `AF_INET` socket is walked past and
     * the datagram leaves from whatever routing preferred. A backend that forwarded the request would
     * report success for a datagram that ignored it — advertising `sourceAddressSelect` on top of that
     * is the #558 defect, so the refusal is what earns the flag.
     *
     * The marker send afterwards is the "nothing was transmitted" half: the receiver's *first*
     * datagram must be the marker, so the refused one cannot have quietly gone out ahead of it.
     */
    @Test
    fun aSourceInTheOtherFamilyIsRefusedWithNothingSent() =
        udpTest {
            val receiver = bind()
            val sender = bind(host = "0.0.0.0")

            val thrown =
                assertFailsWith<DatagramSendException> {
                    sender.send(
                        payload("wrong family"),
                        to = receiver.localAddress,
                        options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("::1", 0)),
                    )
                }
            val error = assertIs<DatagramSendError.SourceAddressUnavailable>(thrown.error)
            assertIs<SourceAddressRejection.WrongFamily>(error.reason)
            assertEquals("::1", error.requestedHost)

            sender.send(payload("marker"), to = receiver.localAddress)
            assertEquals("marker", receiver.recvDatagram().payload.readString(6))
        }

    /**
     * A source no interface holds is reported as such rather than as an unreachable *destination*.
     *
     * This is also the strongest proof that the control message reaches the kernel at all: the
     * destination is plain loopback, so a send that dropped the cmsg would succeed. It can only fail
     * because the kernel read the pin and could not build a route out of it.
     *
     * `192.0.2.0/24` is RFC 5737 TEST-NET-1 — reserved for documentation and never assigned to a real
     * interface, so no host this suite runs on can accidentally own it.
     */
    @Test
    fun anIpv4SourceThisHostDoesNotHoldIsReportedAsTheSource() =
        udpTest {
            val receiver = bind()
            val sender = bind(host = "0.0.0.0")

            val thrown =
                assertFailsWith<DatagramSendException> {
                    sender.send(
                        payload("no such source"),
                        to = receiver.localAddress,
                        options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("192.0.2.1", 0)),
                    )
                }
            val error = assertIs<DatagramSendError.SourceAddressUnavailable>(thrown.error)
            // Not DatagramSendError.Unreachable, which is what the raw ENETUNREACH would have said —
            // and what a migration trigger branches on. The peer is reachable; the source is not ours.
            assertIs<SourceAddressRejection.NotAssigned>(error.reason)
            assertEquals("192.0.2.1", error.requestedHost)
        }

    /**
     * The other side of that classification: a pinned send that fails for a reason which is *not* its
     * source keeps the member it would have had unpinned.
     *
     * Without this, "report SourceAddressUnavailable whenever a pinned send fails" would pass every
     * other test in this file while quietly swallowing every real send failure a server can hit — the
     * reply path names a source on *every* datagram, so that mistake would rename the whole taxonomy.
     *
     * A broadcast destination on a socket without `SO_BROADCAST` is the hermetic way to fail for a
     * reason the source has nothing to do with: the kernel answers `EACCES` after the route lookup has
     * already accepted the pinned source, so the two are unambiguously separable here.
     */
    @Test
    fun aPinnedSendFailingForAnotherReasonKeepsThatReason() =
        udpTest {
            val sender = bind(host = "0.0.0.0")

            val thrown =
                assertFailsWith<DatagramSendException> {
                    sender.send(
                        payload("broadcast"),
                        to = SocketAddress.ofLiteral("255.255.255.255", 9),
                        // A source this host really does hold, so nothing about it is in question.
                        options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("127.0.0.1", 0)),
                    )
                }
            assertIs<DatagramSendError.NotPermitted>(thrown.error)
        }

    /**
     * The IPv6 send path, end to end: a different cmsg level, type, struct and length from the IPv4
     * one, and the receiver still sees the source this test named.
     *
     * The trick is a **v4-mapped** source on a dual-stack socket, because loopback holds exactly one
     * IPv6 address. Pinning `::1` would ask for the address the kernel was going to choose anyway and
     * would pass just as happily with the control message deleted; `::ffff:127.0.0.2` is carried in
     * the same `in6_pktinfo` through the same `IPV6_PKTINFO` path, and the kernel takes the low 32
     * bits of `ipi6_addr` as the IPv4 source — so a wrong offset, length or level shows up as a
     * source that did not move, or a send that failed outright.
     *
     * Depends on the dual-stack default (`net.ipv6.bindv6only = 0`): with v6-only sockets a datagram
     * cannot reach an IPv4 receiver at all, and this fails loudly rather than quietly proving nothing.
     */
    @Test
    fun anIpv6SocketPinsAV4MappedSourceTheReceiverSees() =
        udpTest {
            val receiver = bind()
            val sender = bind(host = "::")
            val destination = SocketAddress.ofLiteral("::ffff:127.0.0.1", receiver.localAddress.port)

            sender.send(payload("unpinned"), to = destination)
            assertEquals("127.0.0.1", receiver.recvDatagram().peer.host, "the kernel's own choice, as the control")

            sender.send(
                payload("pinned"),
                to = destination,
                options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("::ffff:127.0.0.2", 0)),
            )
            assertEquals("127.0.0.2", receiver.recvDatagram().peer.host, "IPV6_PKTINFO must move the source too")
        }

    /**
     * A wildcard source is the *absence* of a request, not a request naming the address `0.0.0.0`.
     *
     * The kernel reads it that way — its `if (fl4->saddr)` and `addr_type != IPV6_ADDR_ANY` guards
     * skip an all-zero pin and choose the source themselves — so a channel that forwarded one would
     * report an honoured request for a datagram that ignored it. That is not a hypothetical input: a
     * server whose receive path falls back to its own wildcard bind address when the platform reports
     * no per-datagram local address hands exactly this down, on every reply.
     *
     * Both spellings deliver, from the kernel's own choice, without throwing. Neither can be caught by
     * observation alone — an all-zero pin and no pin are indistinguishable on the wire, which is
     * precisely why the defect was invisible — so this locks the contract and
     * [aV4MappedWildcardSourceIsTheAbsenceOfOneToo] is the mutation-sensitive half.
     */
    @Test
    fun aWildcardSourceIsTheAbsenceOfOneNotASilentNoOp() =
        udpTest {
            val receiver = bind()
            val sender = bind(host = "0.0.0.0")
            sender.send(
                payload("v4 wildcard"),
                to = receiver.localAddress,
                options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("0.0.0.0", 0)),
            )
            assertEquals("127.0.0.1", receiver.recvDatagram().peer.host)

            val receiver6 = bind(host = "::1")
            val sender6 = bind(host = "::")
            sender6.send(
                payload("v6 wildcard"),
                to = receiver6.localAddress,
                options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("::", 0)),
            )
            assertEquals("::1", receiver6.recvDatagram().peer.host)
        }

    /**
     * `::ffff:0.0.0.0` is the third spelling of "no source", and the one that shows the decision was
     * actually made: unlike `0.0.0.0` and `::` it is not invisible when it is forwarded. A native IPv6
     * destination sees an address that is not `IPV6_ADDR_ANY`, fails `ipv6_chk_addr` and returns
     * `EINVAL` (measured), so a channel that passed it to the kernel would fail this send outright
     * rather than merely fail to honour it.
     */
    @Test
    fun aV4MappedWildcardSourceIsTheAbsenceOfOneToo() =
        udpTest {
            val receiver = bind(host = "::1")
            val sender = bind(host = "::")
            sender.send(
                payload("mapped wildcard"),
                to = receiver.localAddress,
                options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("::ffff:0.0.0.0", 0)),
            )
            assertEquals("::1", receiver.recvDatagram().peer.host)
        }

    /**
     * An unscoped IPv6 link-local source is refused for the reason it is actually unusable.
     *
     * `fe80::1` is one address per link, so the kernel will neither send from it nor bind it without
     * an interface index — `EINVAL` from both. That second `EINVAL` is what makes the honest reason
     * load-bearing: the locality probe is refused the same way, so a backend that leaned on it would
     * conclude "no interface on this host holds it" about an address the host may hold on every
     * interface it has. The assertion below is as much that it is **not** [NotAssigned] as that it is
     * [SourceAddressRejection.UnscopedLinkLocal].
     *
     * Needs no link-local address on the host: the refusal is decided from the prefix, before any
     * syscall, so it is the same on a machine with none.
     */
    @Test
    fun anUnscopedIpv6LinkLocalSourceSaysItNeedsAScope() =
        udpTest {
            val receiver = bind(host = "::1")
            val sender = bind(host = "::")

            val thrown =
                assertFailsWith<DatagramSendException> {
                    sender.send(
                        payload("unscoped"),
                        to = receiver.localAddress,
                        options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("fe80::1", 0)),
                    )
                }
            val error = assertIs<DatagramSendError.SourceAddressUnavailable>(thrown.error)
            // The type system makes the "not NotAssigned" half of this a compile-time fact rather than
            // a runtime one — assertIs narrows `reason`, so an explicit assertFalse against NotAssigned
            // is statically dead. Stated here instead of asserted, because it is the point of the member.
            assertIs<SourceAddressRejection.UnscopedLinkLocal>(error.reason)
        }

    /**
     * The scope id reaches `ipi6_ifindex`, proven by naming an interface that cannot exist.
     *
     * A *correct* scope proves nothing on loopback — `::1` is unscoped-usable, so pinning it with
     * `scopeId = 1` succeeds whether or not the field is written. An impossible interface index does:
     * `dev_get_by_index` fails and the send comes back `ENODEV`, which can only happen if the index
     * was written. With the field hardcoded to 0 — as it was — this send succeeds and the test fails.
     *
     * The reported member is [DatagramSendError.OsError], not a source rejection, and that is correct:
     * the probe binds `::1` successfully (the kernel ignores `sin6_scope_id` when binding a non
     * link-local address), so this backend positively established that the host holds the address and
     * reports the send's own errno rather than inventing a verdict about the source.
     */
    @Test
    fun anIpv6PinCarriesItsScopeIdToTheKernel() =
        udpTest {
            val receiver = bind(host = "::1")
            val sender = bind(host = "::")
            // ::1 is 0x…0001 in the low half; no host can have this many interfaces.
            val impossiblyScoped =
                LinuxSocketAddress("::1", 0, AddressFamily.IPv6, hi = 0L, lo = 1L, scopeId = Int.MAX_VALUE)

            val thrown =
                assertFailsWith<DatagramSendException> {
                    sender.send(
                        payload("bogus scope"),
                        to = receiver.localAddress,
                        options = DatagramSendOptions(fromLocal = impossiblyScoped),
                    )
                }
            assertEquals(ENODEV, assertIs<DatagramSendError.OsError>(thrown.error).errno)
        }

    /**
     * A received IPv6 local address keeps the interface it arrived on.
     *
     * The send path can only put a scope back into `ipi6_ifindex` if the receive path kept one, and it
     * used to drop the field. Without this, a server on a link-local-only segment records a source it
     * can never send from — the failure the pinning was added to prevent, arrived at from the other
     * end.
     */
    @Test
    fun aReceivedIpv6LocalAddressKeepsItsScopeId() =
        udpTest {
            val receiver = bind(host = "::1")
            val sender = bind(host = "::")
            sender.send(payload("scoped"), to = receiver.localAddress)
            val local = receiver.recvDatagram().localAddress.orNull()
            assertIs<LinuxSocketAddress>(local)
            assertTrue(local.scopeId != 0, "IPV6_PKTINFO reports the arrival interface; it must survive the decode")
        }

    /**
     * A failure the probe cannot attribute says so, instead of guessing either way.
     *
     * An IPv6 multicast address as a *source* is refused by `sendmsg` with `EINVAL` — and `bind`
     * refuses it with `EINVAL` too, so the locality probe cannot answer. Before this was a sealed
     * verdict, any non-zero `bind` return read as "the host does not hold it" and this reported
     * [SourceAddressRejection.NotAssigned]: a claim about the host that was never checked.
     */
    @Test
    fun aFailureTheProbeCannotAttributeIsReportedAsUndetermined() =
        udpTest {
            val receiver = bind(host = "::1")
            val sender = bind(host = "::")

            val thrown =
                assertFailsWith<DatagramSendException> {
                    sender.send(
                        payload("multicast source"),
                        to = receiver.localAddress,
                        options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("ff02::1", 0)),
                    )
                }
            val error = assertIs<DatagramSendError.SourceAddressUnavailable>(thrown.error)
            val reason = assertIs<SourceAddressRejection.Undetermined>(error.reason)
            assertEquals(EINVAL, reason.sendErrno)
            assertEquals(EINVAL, reason.probeErrno)
        }

    /**
     * The pin works on a connected channel too, which takes a different send shape: no `msg_name` at
     * all, the kernel routing to the `connect()`ed peer, and a source the connect already chose.
     *
     * Worth its own test because the source is exactly what a connected socket has already decided —
     * `connect` auto-binds one — and the control message has to override that decision rather than
     * merely fill in a blank. It does: `udp_sendmsg` drops its route cache when a send carries
     * ancillary data and looks the route up again with the named source.
     */
    @Test
    fun aConnectedChannelPinsItsSourceToo() =
        udpTest {
            val receiver = bind()
            val sender = connectTo(receiver.localAddress)

            sender.send(payload("connected"))
            assertEquals("127.0.0.1", receiver.recvDatagram().peer.host, "what connect() chose, as the control")

            sender.send(
                payload("connected pinned"),
                options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("127.0.0.2", 0)),
            )
            assertEquals("127.0.0.2", receiver.recvDatagram().peer.host)
        }

    /**
     * The *native* IPv6 send path — a real IPv6 destination, not the v4-mapped one the test above
     * uses. They are different kernel code: a v4-mapped destination re-enters the IPv4 sender and its
     * cmsg parser, while `::1` stays in `ip6_datagram_send_ctl`, which checks the named source against
     * the interface list itself. Only this one reaches that check.
     *
     * It has to be the negative case, because loopback holds exactly one IPv6 address: pinning `::1`
     * would name the address the kernel was going to choose anyway and would pass just as happily
     * with the control message deleted. Refusing `2001:db8::1` (RFC 3849, the documentation prefix)
     * cannot — the send would otherwise succeed.
     */
    @Test
    fun anIpv6SourceThisHostDoesNotHoldIsReportedAsTheSource() =
        udpTest {
            val receiver = bind(host = "::1")
            val sender = bind(host = "::")

            val thrown =
                assertFailsWith<DatagramSendException> {
                    sender.send(
                        payload("no such v6 source"),
                        to = receiver.localAddress,
                        options = DatagramSendOptions(fromLocal = SocketAddress.ofLiteral("2001:db8::1", 0)),
                    )
                }
            val error = assertIs<DatagramSendError.SourceAddressUnavailable>(thrown.error)
            assertIs<SourceAddressRejection.NotAssigned>(error.reason)
        }
}
