package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.PinnedRadioRefusal
import com.ditchoom.socket.quic.QuicOptionName
import com.ditchoom.socket.quic.QuicOptionRefusal
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.StandbyLink
import com.ditchoom.socket.quic.StreamOpenPolicy
import com.ditchoom.socket.quic.trace.QlogBudget
import com.ditchoom.socket.quic.trace.QlogDirectory
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * What the core does with every value of each option it decides. Each option's values are listed by an exhaustive `when`,
 * so a value added to one fails to compile here until its test names the verdict.
 */
class OptionVerdictTests {
    private val base = QuicOptions(alpnProtocols = listOf("test"))

    private fun verdict(
        options: QuicOptions,
        role: QuicRole = QuicRole.Client,
        capture: TraceCapture = TraceCapture.Off,
        standby: StandbyRadio = StandbyRadio.Absent,
    ) = verdictOn(options, role, capture, standby)

    private fun refusalOf(verdict: OptionVerdict): QuicOptionRefusal =
        when (verdict) {
            is OptionVerdict.Refused -> verdict.refusal
            is OptionVerdict.PinRefused -> verdict.refusal
            is OptionVerdict.Honoured -> throw AssertionError("expected a refusal, was honoured")
        }

    private fun assertHonoured(verdict: OptionVerdict) = assertEquals(OptionVerdict.Honoured, verdict)

    @Test
    fun everyMigrationPolicyIsDecided() {
        for (policy in listOf(
            MigrationPolicy.Forbidden,
            MigrationPolicy.Manual,
            MigrationPolicy.Automatic,
            MigrationPolicy.Pinned(NetworkKind.Cellular),
        )) {
            val verdict = verdict(base.copy(migration = policy))
            when (policy) {
                MigrationPolicy.Forbidden, MigrationPolicy.Manual, MigrationPolicy.Automatic -> assertHonoured(verdict)
                is MigrationPolicy.Pinned -> {
                    assertEquals(PinnedRadioRefusal.EngineCannotPin, refusalOf(verdict))
                    assertEquals(QuicOptionName.Migration, refusalOf(verdict).option)
                    assertEquals(PinnedRadioRefusal.EngineCannotPin, refusalOf(verdict(base.copy(migration = policy), QuicRole.Server)))
                }
            }
        }
    }

    @Test
    fun everyStreamOpenPolicyIsDecided() {
        for (policy in listOf(StreamOpenPolicy.UntilClosed, StreamOpenPolicy.Bounded(5.seconds))) {
            val verdict = verdict(base.copy(streamOpenPolicy = policy))
            when (policy) {
                StreamOpenPolicy.UntilClosed -> assertHonoured(verdict)
                is StreamOpenPolicy.Bounded -> {
                    assertEquals(QuicOptionRefusal.StreamOpenNotBounded(5.seconds), refusalOf(verdict))
                    assertEquals(QuicOptionName.StreamOpenPolicy, refusalOf(verdict).option)
                }
            }
        }
    }

    @Test
    fun everyStandbyLinkIsDecidedOnAPlatformWithAStandbyRadio() {
        for (link in listOf(StandbyLink.OnDemand, StandbyLink.KeepCellularReady)) {
            val verdict = verdict(base.copy(standbyLink = link), standby = StandbyRadio.Present)
            when (link) {
                StandbyLink.OnDemand -> assertHonoured(verdict)
                StandbyLink.KeepCellularReady -> {
                    assertEquals(QuicOptionRefusal.StandbyRadioNotHeld, refusalOf(verdict))
                    assertEquals(QuicOptionName.StandbyLink, refusalOf(verdict).option)
                }
            }
        }
    }

    @Test
    fun aStandbyLinkIsReadOnlyByAClientUnderAutomaticMigrationOnAPlatformWithTheRadio() {
        val keep = base.copy(standbyLink = StandbyLink.KeepCellularReady)
        assertHonoured(verdict(keep, standby = StandbyRadio.Absent))
        assertHonoured(verdict(keep, role = QuicRole.Server, standby = StandbyRadio.Present))
        assertHonoured(verdict(keep.copy(migration = MigrationPolicy.Manual), standby = StandbyRadio.Present))
        assertHonoured(verdict(keep.copy(migration = MigrationPolicy.Forbidden), standby = StandbyRadio.Present))
    }

    @Test
    fun everyQlogTargetIsDecided() {
        val budgeted = QlogTarget.Budgeted(QlogDirectory("/qlog", QlogBudget.of(1L shl 20, 2, 1L shl 26, 4)) { }, "conn")
        for (target in listOf(QlogTarget.Off, QlogTarget.File("/qlog/a.sqlog"), budgeted)) {
            val verdict = verdict(base, capture = TraceCapture.On(QuicTraceRecorder(TraceSink { }), qlog = target))
            when (target) {
                QlogTarget.Off, is QlogTarget.File -> assertHonoured(verdict)
                is QlogTarget.Budgeted -> {
                    assertEquals(QuicOptionRefusal.QlogNotBudgeted(budgeted), refusalOf(verdict))
                    assertEquals(QuicOptionName.Trace, refusalOf(verdict).option)
                }
            }
        }
        assertHonoured(verdict(base, capture = TraceCapture.Off))
    }

    @Test
    fun theDefaultOptionsAreHonouredEvenWhereThereIsAStandbyRadio() {
        assertEquals(StandbyLink.OnDemand, base.standbyLink)
        assertHonoured(verdict(base, standby = StandbyRadio.Present))
        assertHonoured(verdict(base, role = QuicRole.Server, standby = StandbyRadio.Present))
    }
}
