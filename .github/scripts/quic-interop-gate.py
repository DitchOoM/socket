#!/usr/bin/env python3
"""The QUIC interop gate: the runner's verdict for every cell, judged against a checked-in expectation.

A cell is one direction, one peer and one test. A direction may carry a lane prefix (macos-arm64-our-client)
for the native lanes that run without the runner (test-harness/quic-interop/native-pair.py):
  our-client            our client against the peer's server
  our-server            the peer's client against our server
  our-client-migrating  our client's `transfer` with six active migrations (QuicInteropEndpoint.kt), plus the
                        pseudo-test `active-migration`: every move the client made succeeded

  our-server-raised-streams  `multiplexing` against our server advertising 128 bidirectional streams
                        (SERVER_PARAMS=streams=raised), beside the standard 100 in our-server

Expectations (test-harness/quic-interop/expectations.json) map direction -> peer -> test -> one of
  "pass"                         must succeed
  "unsupported"                  one side does not implement it
  {"known-failure": <reason>}    fails today, for the stated reason
  {"flaky": <reason>}            passes, or fails for the stated reason
where <reason> is {"why": "<why>", "signature": {"kind": ..., ...}} (proven: a failure must match the
signature in the cell's evidence, written by quic-interop-evidence.py; a failure that does not is a REGRESSION;
SIGNATURES lists the kinds and what each checks) or "<why>" (unproven: any failure is accepted). Unproven is a
transition: every run lists each unproven entry by name, and a pull request that adds one says in the pull
request what evidence is missing.

Verdicts: a cell expected to pass that fails (and fails its one retry), turns unsupported, or was skipped is a
REGRESSION. A cell whose expectation no longer matches (a known failure that passes, an unsupported test that
now runs) is STALE: the file must be updated, so it is never silently out of date. A cell that ran but has no
expectation is NEW. REGRESSION, NEW and MISSING always fail the gate; STALE fails it with --stale=fail.

Subcommands:
  retry <expectations> <direction> <peer> <runner.json>   tests to run once more: expected to pass, did not
  judge <expectations> <results-dir> [--stale=warn|fail] [--record=<file>]
"""
import enum
import json
from dataclasses import dataclass
import os
import re
import sys

MIGRATION_TEST = "active-migration"
CELL_FILE = re.compile(r"((?:[a-z0-9]+-)*our-(?:client-migrating|client|server-raised-streams|server))__([^.]+)\.json")
STREAM_LIMIT_ERROR = 0x4
FRAME_ENCODING_ERROR = 0x7
SIGNAL_EXIT_BASE = 128


class ExpectationError(Exception):
    """expectations.json holds an entry this gate cannot judge."""


@dataclass(frozen=True)
class Matched:
    """The evidence shows the signature's failure; [shown] says what it saw."""

    shown: str


@dataclass(frozen=True)
class NotMatched:
    """The evidence does not show the signature's failure."""


class Signal(enum.Enum):
    SIGABRT = 6
    SIGBUS = 7
    SIGKILL = 9
    SIGSEGV = 11


class PeerExceededStreamLimit:
    """Our endpoint closed with STREAM_LIMIT_ERROR after the peer opened more bidirectional streams than we
    ever allowed: our initial_max_streams_bidi and every MAX_STREAMS we sent were below the count it opened."""

    kind = "peer-exceeded-stream-limit"

    def __init__(self, fields):
        if fields:
            raise ExpectationError(f"{self.kind} takes no fields, got {sorted(fields)}")

    def match(self, test_evidence, peer_role):
        for c in test_evidence.get("connections", []):
            allowed = max(c["local_initial_max_streams_bidi"], c["max_streams_bidi_sent"])
            closed = {"space": "transport", "code": STREAM_LIMIT_ERROR} in c["local_closes"]
            if closed and c["peer_bidi_streams_opened"] > allowed:
                return Matched(f"peer opened {c['peer_bidi_streams_opened']} bidi streams, allowed {allowed}; we closed 0x4")
        return NotMatched()


class PeerProcessDied:
    """The peer's container exited on the named signal (exit code 128 + signal)."""

    kind = "peer-process-died"

    def __init__(self, fields):
        if set(fields) != {"signal"}:
            raise ExpectationError(f"{self.kind} takes exactly a signal, got {sorted(fields)}")
        try:
            self.signal = Signal[fields["signal"]]
        except KeyError:
            raise ExpectationError(f"{self.kind}: unknown signal {fields['signal']!r}") from None

    def match(self, test_evidence, peer_role):
        exits = test_evidence.get("exits", {})
        if peer_role in exits and exits[peer_role] == SIGNAL_EXIT_BASE + self.signal.value:
            return Matched(f"peer {peer_role} exited with {exits[peer_role]} ({self.signal.name})")
        return NotMatched()


class MigrationRefusedPeerForbids:
    """Our client made no move because the peer's transport parameters forbid active migration: every
    connection's remote disable_active_migration is set, and the client's typed result for its first move is
    PeerForbids with none succeeding."""

    kind = "migration-refused-peer-forbids"

    def __init__(self, fields):
        if fields:
            raise ExpectationError(f"{self.kind} takes no fields, got {sorted(fields)}")

    def match(self, test_evidence, peer_role):
        connections = test_evidence.get("connections", [])
        forbidden = bool(connections) and all(c["remote_disable_active_migration"] for c in connections)
        refused = [m for m in test_evidence.get("migrations", []) if m["succeeded"] == 0 and m.get("first_unmoved") == "PeerForbids"]
        if forbidden and refused:
            return Matched("peer sets disable_active_migration; our first move: PeerForbids")
        return NotMatched()


NOT_ESTABLISHED = "Failed to establish connection"


def no_fields(kind, fields):
    if fields:
        raise ExpectationError(f"{kind} takes no fields, got {sorted(fields)}")


def positive_int(kind, fields, name):
    if set(fields) != {name} or not isinstance(fields[name], int) or fields[name] <= 0:
        raise ExpectationError(f"{kind} takes exactly a positive integer {name}, got {fields!r}")
    return fields[name]


class PeerSilentUntilOurTimeout:
    """Nothing of ours was in flight and the peer sent nothing for at least silence_s seconds before the connection
    ended, and we never waited more than our_wait_s to send again while data of ours was in flight: the next move
    was the peer's (its handshake data or a response), and it made none in time."""

    kind = "peer-silent-until-our-timeout"

    def __init__(self, fields):
        if set(fields) != {"silence_s", "our_wait_s"}:
            raise ExpectationError(f"{self.kind} takes exactly silence_s and our_wait_s, got {sorted(fields)}")
        self.silence_ms = positive_int(self.kind, {"silence_s": fields["silence_s"]}, "silence_s") * 1000
        self.our_wait_ms = positive_int(self.kind, {"our_wait_s": fields["our_wait_s"]}, "our_wait_s") * 1000

    def match(self, test_evidence, peer_role):
        for c in test_evidence.get("connections", []):
            if "last_rx_ms" not in c or "end_ms" not in c or c["bytes_in_flight_at_end"] != 0:
                continue
            if c["longest_wait_in_flight_ms"] > self.our_wait_ms:
                continue
            silent = c["end_ms"] - c["last_rx_ms"]
            if silent >= self.silence_ms:
                return Matched(f"nothing of ours in flight; the peer was silent {silent / 1000:.1f} s until {c['qlog']} ended")
        return NotMatched()


class PeerNeverResentServerHello:
    """Our client's handshake never completed: the server acknowledged our Initial packets but no Initial packet of
    its carrying CRYPTO data (its ServerHello) ever arrived, so it never sent it again after losing it."""

    kind = "peer-never-resent-server-hello"

    def __init__(self, fields):
        no_fields(self.kind, fields)

    def match(self, test_evidence, peer_role):
        for c in test_evidence.get("connections", []):
            if c.get("close_reason") == NOT_ESTABLISHED and c["rx_initial_crypto"] == 0 and c["rx_initial_acks"] > 0:
                return Matched(f"{c['qlog']}: {c['rx_initial_acks']} Initial acknowledgements, no ServerHello")
        return NotMatched()


class PeerFinishedNeverArrived:
    """A connection our server never answered on: the client acknowledged our Handshake packets but none of its own
    carrying CRYPTO data (its Finished) ever arrived, so it never sent it again after losing it."""

    kind = "peer-finished-never-arrived"

    def __init__(self, fields):
        no_fields(self.kind, fields)

    def match(self, test_evidence, peer_role):
        for c in test_evidence.get("connections", []):
            if c["fin_sent_streams"] == 0 and c["rx_handshake_crypto"] == 0 and c["rx_handshake_acks"] > 0:
                return Matched(f"{c['qlog']}: {c['rx_handshake_acks']} Handshake acknowledgements, no Finished")
        return NotMatched()


class PeerResetDuplicatedRequest:
    """The peer reset a request stream with final size 0 after we had sent the request's first bytes more than once:
    it reacted to a duplicate STREAM frame, which RFC 9000 §2.2 has a receiver discard."""

    kind = "peer-reset-duplicated-request"

    def __init__(self, fields):
        no_fields(self.kind, fields)

    def match(self, test_evidence, peer_role):
        for c in test_evidence.get("connections", []):
            for r in c["peer_resets"]:
                if r["final_size"] == 0 and r["our_copies"] > 1:
                    return Matched(f"{c['qlog']}: reset of stream {r['stream_id']} after {r['our_copies']} copies of its request")
        return NotMatched()


class PeerCountedExtraHandshake:
    """The runner counted more handshakes than requests, while our client opened exactly one connection per request:
    the peer answered one of our connections twice, with a second connection of its own."""

    kind = "peer-counted-extra-handshake"

    def __init__(self, fields):
        no_fields(self.kind, fields)

    def match(self, test_evidence, peer_role):
        counted = test_evidence.get("runner", {}).get("handshakes")
        ours = len(test_evidence.get("connections", []))
        if counted and counted["got"] > counted["expected"] == ours:
            return Matched(f"runner counted {counted['got']} handshakes for our {ours} connections")
        return NotMatched()


class PeerUsedFewerConnections:
    """The runner counted fewer handshakes than the test asks for, and our server saw fewer connections than that
    too: the peer's client fetched its files over fewer connections than the test requires."""

    kind = "peer-used-fewer-connections"

    def __init__(self, fields):
        no_fields(self.kind, fields)

    def match(self, test_evidence, peer_role):
        counted = test_evidence.get("runner", {}).get("handshakes")
        opened = len(test_evidence.get("connections", []))
        if counted and counted["got"] < counted["expected"] and 0 < opened < counted["expected"]:
            return Matched(f"runner counted {counted['got']} of {counted['expected']} handshakes; the peer opened {opened} connection(s)")
        return NotMatched()


class RunnerTimeLimitWhileWeAnswered:
    """The runner stopped the test at its time limit while our server had answered every request it received within
    answer_ms: the time went to the peer's client."""

    kind = "runner-time-limit-while-we-answered"

    def __init__(self, fields):
        self.answer_ms = positive_int(self.kind, fields, "answer_ms")

    def match(self, test_evidence, peer_role):
        limit = test_evidence.get("runner", {}).get("time_limit_s")
        answers = [c["max_answer_ms"] for c in test_evidence.get("connections", []) if "max_answer_ms" in c]
        if limit and answers and max(answers) <= self.answer_ms:
            return Matched(f"runner stopped at {limit} s; our slowest answer took {max(answers):.0f} ms")
        return NotMatched()


class PeerRequestArrivedLate:
    """The peer's client sent its first request at least after_s seconds after our server confirmed the handshake
    (sent HANDSHAKE_DONE): the client spent the connection's life on its own retransmission timers."""

    kind = "peer-request-arrived-late"

    def __init__(self, fields):
        self.after_ms = positive_int(self.kind, fields, "after_s") * 1000

    def match(self, test_evidence, peer_role):
        for c in test_evidence.get("connections", []):
            if "handshake_done_sent_ms" in c and "first_request_ms" in c:
                late = c["first_request_ms"] - c["handshake_done_sent_ms"]
                if late >= self.after_ms:
                    return Matched(f"{c['qlog']}: the first request arrived {late / 1000:.1f} s after our HANDSHAKE_DONE")
        return NotMatched()


class SimLostEveryDatagramToUs:
    """A connection of ours received nothing, and the simulator corrupted or dropped every one of at least
    min_datagrams datagrams it carried to one of our ports."""

    kind = "sim-lost-every-datagram-to-us"

    def __init__(self, fields):
        self.min_datagrams = positive_int(self.kind, fields, "min_datagrams")

    def match(self, test_evidence, peer_role):
        deaf = [c for c in test_evidence.get("connections", []) if c["rx_packets"] == 0]
        lost = [p for p in test_evidence.get("sim_to_us", []) if p["delivered"] == 0 and p["datagrams"] >= self.min_datagrams]
        if deaf and lost:
            return Matched(f"{deaf[0]['qlog']} received nothing; the simulator delivered 0 of {lost[0]['datagrams']} datagrams to port {lost[0]['port']}")
        return NotMatched()


class SimLostEveryDatagramToThePeer:
    """A connection of ours received the peer's handshake CRYPTO data but could not answer it (it sent no CRYPTO of
    its own: the ClientHello never arrived whole), and the simulator corrupted or dropped every one of at least
    min_datagrams datagrams we sent to one of the peer's ports, so our acknowledgements never told it what to
    re-send."""

    kind = "sim-lost-every-datagram-to-the-peer"

    def __init__(self, fields):
        self.min_datagrams = positive_int(self.kind, fields, "min_datagrams")

    def match(self, test_evidence, peer_role):
        stuck = [c for c in test_evidence.get("connections", []) if c["rx_initial_crypto"] > 0 and c["tx_crypto"] == 0]
        lost = [p for p in test_evidence.get("sim_to_peer", []) if p["delivered"] == 0 and p["datagrams"] >= self.min_datagrams]
        if stuck and lost:
            return Matched(f"{stuck[0]['qlog']} could not answer; the simulator delivered 0 of {lost[0]['datagrams']} of our datagrams to port {lost[0]['port']}")
        return NotMatched()


class PeerLeftADownloadEmpty:
    """The peer's client exited 0 although the runner found a download it wrote empty, while our server answered
    a request on every connection: the client reported success for a file it never received."""

    kind = "peer-left-a-download-empty"

    def __init__(self, fields):
        no_fields(self.kind, fields)

    def match(self, test_evidence, peer_role):
        empty = test_evidence.get("runner", {}).get("empty_downloads", 0)
        connections = test_evidence.get("connections", [])
        answered = bool(connections) and all(c["fin_sent_streams"] > 0 for c in connections)
        if empty and answered and test_evidence.get("exits", {}).get(peer_role) == 0:
            return Matched(f"peer {peer_role} exited 0 with {empty} empty download(s); we answered on all {len(connections)} connections")
        return NotMatched()


class PeerRequestNeverArrived:
    """A connection whose handshake our server confirmed (HANDSHAKE_DONE sent), on which the peer kept sending
    packets, but no request ever arrived: the client never sent it, or never sent it again after losing it."""

    kind = "peer-request-never-arrived"

    def __init__(self, fields):
        self.min_packets = positive_int(self.kind, fields, "min_packets")

    def match(self, test_evidence, peer_role):
        for c in test_evidence.get("connections", []):
            confirmed = "handshake_done_sent_ms" in c
            if confirmed and "first_request_ms" not in c and c["fin_sent_streams"] == 0 and c["rx_packets"] >= self.min_packets:
                return Matched(f"{c['qlog']}: {c['rx_packets']} packets from the peer after our HANDSHAKE_DONE, no request")
        return NotMatched()


class PeerHandshakePacketsNeverArrived:
    """Our server answered a ClientHello and re-sent its flight until its handshake timeout without one
    Handshake-space packet from the client arriving, while the simulator corrupted or dropped at least
    min_lost of the datagrams one client port sent us: every copy of the client's Finished was lost."""

    kind = "peer-handshake-packets-never-arrived"

    def __init__(self, fields):
        self.min_lost = positive_int(self.kind, fields, "min_lost")

    def match(self, test_evidence, peer_role):
        lost = [p for p in test_evidence.get("sim_from_peer", []) if p["datagrams"] - p["delivered"] >= self.min_lost]
        for c in test_evidence.get("connections", []):
            unanswered = c["rx_handshake_crypto"] == 0 and c["rx_handshake_acks"] == 0
            if c.get("close_reason") == NOT_ESTABLISHED and c["rx_initial_crypto"] > 0 and c["tx_crypto"] > 1 and unanswered and lost:
                gone = lost[0]["datagrams"] - lost[0]["delivered"]
                return Matched(f"{c['qlog']}: no Handshake packet arrived; the simulator lost {gone} of {lost[0]['datagrams']} datagrams from port {lost[0]['port']}")
        return NotMatched()


class PeerServerDidNotChallengeNewPathFirst:
    """The runner failed a rebinding test because the peer server's first packet to our client's new address
    carried no PATH_CHALLENGE, while our client exited 0: the verdict is on the server's own packets."""

    kind = "peer-server-did-not-challenge-new-path-first"

    def __init__(self, fields):
        no_fields(self.kind, fields)

    def match(self, test_evidence, peer_role):
        count = test_evidence.get("runner", {}).get("server_first_packets_without_path_challenge", 0)
        if peer_role == "server" and count and test_evidence.get("exits", {}).get("client") == 0:
            return Matched(f"runner: the server's first packet on {count} new path(s) had no PATH_CHALLENGE; our client exited 0")
        return NotMatched()


class AnyOf:
    """The cell has more than one proven failure; a failure must match one of the listed signatures (each a
    {"kind": ..., ...fields} of another kind)."""

    kind = "any-of"

    def __init__(self, fields):
        if set(fields) != {"signatures"} or not isinstance(fields["signatures"], list) or len(fields["signatures"]) < 2:
            raise ExpectationError(f"{self.kind} takes exactly signatures, a list of two or more, got {fields!r}")
        self.signatures = []
        for raw in fields["signatures"]:
            inner = dict(raw)
            kind = inner.pop("kind", "")
            if kind not in SIGNATURES or kind == self.kind:
                raise ExpectationError(f"{self.kind}: unknown or nested signature kind {kind!r}")
            self.signatures.append(SIGNATURES[kind](inner))

    def match(self, test_evidence, peer_role):
        for signature in self.signatures:
            result = signature.match(test_evidence, peer_role)
            if isinstance(result, Matched):
                return Matched(f"{signature.kind}: {result.shown}")
        return NotMatched()


class FrameEncodingErrorFromZeroLengthCidPeer:
    """Our endpoint closed with FRAME_ENCODING_ERROR on a connection whose peer uses a zero-length source
    connection id (its Initial's scil is 0). Such a peer has no connection ids to issue, so a NEW_CONNECTION_ID
    from it carries an invalid length (RFC 9000 §19.15); quiche's qlog does not record the frame it rejected."""

    kind = "frame-encoding-error-from-zero-length-cid-peer"

    def __init__(self, fields):
        if fields:
            raise ExpectationError(f"{self.kind} takes no fields, got {sorted(fields)}")

    def match(self, test_evidence, peer_role):
        for c in test_evidence.get("connections", []):
            closed = {"space": "transport", "code": FRAME_ENCODING_ERROR} in c["local_closes"]
            if closed and c.get("peer_source_cid_lengths") == [0]:
                return Matched("peer's source connection id is zero-length; we closed 0x7 (FRAME_ENCODING_ERROR)")
        return NotMatched()


SIGNATURES = {
    s.kind: s
    for s in (
        PeerExceededStreamLimit,
        PeerProcessDied,
        MigrationRefusedPeerForbids,
        FrameEncodingErrorFromZeroLengthCidPeer,
        PeerSilentUntilOurTimeout,
        PeerNeverResentServerHello,
        PeerFinishedNeverArrived,
        PeerResetDuplicatedRequest,
        PeerCountedExtraHandshake,
        PeerUsedFewerConnections,
        RunnerTimeLimitWhileWeAnswered,
        PeerRequestArrivedLate,
        SimLostEveryDatagramToUs,
        SimLostEveryDatagramToThePeer,
        PeerLeftADownloadEmpty,
        PeerRequestNeverArrived,
        PeerServerDidNotChallengeNewPathFirst,
        PeerHandshakePacketsNeverArrived,
        AnyOf,
    )
}


class Reason:
    """Why a cell is a known failure or flaky; proven when it carries a signature."""

    def __init__(self, raw):
        if isinstance(raw, str):
            self.why, self.signature = raw, None
            return
        if not isinstance(raw, dict) or set(raw) != {"why", "signature"}:
            raise ExpectationError(f"a reason is a string or {{why, signature}}, got {raw!r}")
        fields = dict(raw["signature"])
        kind = fields.pop("kind", "")
        if kind not in SIGNATURES:
            raise ExpectationError(f"unknown signature kind {kind!r}; known: {sorted(SIGNATURES)}")
        self.why, self.signature = raw["why"], SIGNATURES[kind](fields)


def runner_results(path):
    """{test name: succeeded|failed|unsupported|skipped} from one runner JSON holding one client and one server."""
    with open(path) as f:
        r = json.load(f)
    names = {abbr: t["name"] for abbr, t in r.get("tests", {}).items()}
    out = {}
    for group in ("results", "measurements"):
        cells = r.get(group) or []
        if not cells:
            continue
        for entry in cells[0] or []:
            name = names.get(entry["abbr"], entry["abbr"])
            out[name] = entry.get("result") or "skipped"
    return out


def migration_outcome(summary_path):
    """The `active-migration` pseudo-test from the client's MIGRATION-SUMMARY line."""
    if not os.path.exists(summary_path):
        return "failed", "no MIGRATION-SUMMARY (the client did not finish the transfer)"
    with open(summary_path) as f:
        line = f.read().strip()
    if not line:
        return "failed", "no MIGRATION-SUMMARY (the client did not finish the transfer)"
    attempted = re.search(r"attempted=(\d+)", line)
    succeeded = re.search(r"succeeded=(\d+)", line)
    if attempted and succeeded and int(attempted.group(1)) > 0 and attempted.group(1) == succeeded.group(1):
        return "succeeded", line
    return "failed", line


def expectation(exp, direction, peer, test):
    """(kind, Reason) for one cell; the Reason of a pass, unsupported or absent cell has no signature."""
    value = exp.get(direction, {}).get(peer, {}).get(test)
    if value is None:
        return "absent", Reason("")
    if isinstance(value, str):
        return value, Reason("")
    (kind, raw), = value.items()
    return kind, Reason(raw)


def peer_role(direction):
    return "client" if "our-server" in direction else "server"


def load_evidence(results_dir):
    """{(direction, peer): {test: evidence}} from every <direction>__<peer>.evidence.json."""
    out = {}
    for name in os.listdir(results_dir):
        if name.endswith(".evidence.json"):
            with open(os.path.join(results_dir, name)) as f:
                e = json.load(f)
            out[(e["direction"], e["peer"])] = e["tests"]
    return out


def cmd_retry(args):
    exp_path, direction, peer, results_path = args
    with open(exp_path) as f:
        exp = json.load(f)
    actual = runner_results(results_path)
    again = [t for t, r in actual.items() if r != "succeeded" and expectation(exp, direction, peer, t)[0] == "pass"]
    print(",".join(sorted(again)))
    return 0


def load_cells(results_dir):
    """{(direction, peer, test): (result, detail)} with a passing retry turning a failure into 'retried'."""
    cells = {}
    for name in sorted(os.listdir(results_dir)):
        m = CELL_FILE.fullmatch(name)
        if not m:
            continue
        direction, peer = m.groups()
        first = runner_results(os.path.join(results_dir, name))
        retry_path = os.path.join(results_dir, f"{direction}__{peer}.retry")
        retried = runner_results(retry_path) if os.path.exists(retry_path) else {}
        for test, result in first.items():
            if result == "failed" and retried.get(test) == "succeeded":
                cells[(direction, peer, test)] = ("retried", "failed once, passed on retry")
            else:
                cells[(direction, peer, test)] = (result, "")
        if direction.endswith("our-client-migrating"):
            cells[(direction, peer, MIGRATION_TEST)] = migration_outcome(
                os.path.join(results_dir, f"{direction}__{peer}.summary")
            )
    return cells


def judge_failure(kind, reason, test_evidence, role):
    """(verdict, accepted, note) for a failed cell expected to fail (known-failure) or allowed to (flaky)."""
    if reason.signature is None:
        return f"{kind} (unproven)", True, reason.why
    if test_evidence is None:
        return "REGRESSION", False, f"no evidence to check the {reason.signature.kind} signature against"
    result = reason.signature.match(test_evidence, role)
    if isinstance(result, Matched):
        return f"{kind} (signature)", True, result.shown
    return "REGRESSION", False, f"failed, but not as {reason.signature.kind}: {reason.why}"


def judge_cell(kind, result):
    """(verdict, accepted) for one cell's expectation kind and actual result."""
    if kind == "absent":
        return "NEW", False
    if kind == "flaky":
        return "flaky", True
    if kind == "pass":
        if result == "succeeded":
            return "ok", True
        if result == "retried":
            return "passed on retry", True
        return "REGRESSION", False
    if kind == "unsupported":
        return ("ok", True) if result == "unsupported" else ("STALE", False)
    if kind == "known-failure":
        return ("ok", True) if result == "failed" else ("STALE", False)
    return f"bad expectation {kind!r}", False


def recorded(result, previous_kind, previous_raw):
    if result in ("succeeded", "retried"):
        return "pass"
    if result == "unsupported":
        return "unsupported"
    if previous_kind in ("known-failure", "flaky") and previous_raw:
        return {previous_kind: previous_raw}
    return {"known-failure": "TODO: root cause"}


def cmd_judge(args):
    stale = "warn"
    record = ""
    positional = []
    for a in args:
        if a.startswith("--stale="):
            stale = a.split("=", 1)[1]
        elif a.startswith("--record="):
            record = a.split("=", 1)[1]
        else:
            positional.append(a)
    exp_path, results_dir = positional
    with open(exp_path) as f:
        exp = json.load(f)
    for direction, peers in exp.items():
        for peer, tests in peers.items():
            for test in tests:
                expectation(exp, direction, peer, test)  # raises ExpectationError on an entry it cannot judge
    cells = load_cells(results_dir)
    evidence = load_evidence(results_dir)
    ran_rows = {(d, p) for d, p, _ in cells}

    rows = []
    failing = 0
    for (direction, peer, test), (result, detail) in sorted(cells.items()):
        kind, reason = expectation(exp, direction, peer, test)
        note = reason.why or detail
        if kind in ("known-failure", "flaky") and result == "failed":
            # The active-migration pseudo-test is judged on the transfer it ran in.
            ran = "transfer" if test == MIGRATION_TEST else test
            verdict, accepted, note = judge_failure(kind, reason, evidence.get((direction, peer), {}).get(ran), peer_role(direction))
        else:
            verdict, accepted = judge_cell(kind, result)
        if verdict == "STALE" and stale == "warn":
            accepted = True
        if not accepted:
            failing += 1
        rows.append((verdict, direction, peer, test, result, kind, note))
    lanes = {d.split("our-")[0] for d, _ in ran_rows}
    for direction in sorted(exp):
        if direction.split("our-")[0] not in lanes:
            continue  # another lane's expectations; this run did not cover that lane
        for peer, tests in exp[direction].items():
            if (direction, peer) not in ran_rows:
                rows.append(("MISSING", direction, peer, "*", "not run", "", "the peer's run produced no result"))
                failing += 1

    noteworthy = [r for r in rows if r[0] not in ("ok",)]
    unproven = [
        (direction, peer, test, kind, reason.why)
        for direction, peers in sorted(exp.items())
        if direction.split("our-")[0] in lanes
        for peer, tests in sorted(peers.items())
        for test in sorted(tests)
        for kind, reason in [expectation(exp, direction, peer, test)]
        if kind in ("known-failure", "flaky") and reason.signature is None
    ]
    print("## QUIC interop gate")
    print()
    print(f"{len(cells)} cells; {failing} failing the gate; stale expectations {'fail' if stale == 'fail' else 'warn'}; "
          f"{len(unproven)} unproven expectations (any failure accepted).")
    print()
    if noteworthy:
        print("| Verdict | Direction | Peer | Test | Result | Expected | Note |")
        print("| --- | --- | --- | --- | --- | --- | --- |")
        for r in noteworthy:
            note = r[6].replace("|", "\\|")
            print(f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]} | {r[5]} | {note} |")
        print()

    if unproven:
        print(f"### Unproven expectations ({len(unproven)}): each needs a signature")
        print()
        print("| Direction | Peer | Test | Expected | Why |")
        print("| --- | --- | --- | --- | --- |")
        for direction, peer, test, kind, why in unproven:
            print(f"| {direction} | {peer} | {test} | {kind} | {why.replace('|', chr(92) + '|')} |")
        print()

    if record:
        out = {}
        for (direction, peer, test), (result, _) in sorted(cells.items()):
            kind = expectation(exp, direction, peer, test)[0]
            raw = exp.get(direction, {}).get(peer, {}).get(test)
            previous = next(iter(raw.values())) if isinstance(raw, dict) else ""
            out.setdefault(direction, {}).setdefault(peer, {})[test] = recorded(result, kind, previous)
        with open(record, "w") as f:
            json.dump(out, f, indent=2, sort_keys=True)
            f.write("\n")
        print(f"Recorded today's results as expectations in {record}.")
        return 0
    return 1 if failing else 0


def main(argv):
    if len(argv) < 2 or argv[1] not in ("retry", "judge"):
        print(__doc__, file=sys.stderr)
        return 2
    return {"retry": cmd_retry, "judge": cmd_judge}[argv[1]](argv[2:])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
