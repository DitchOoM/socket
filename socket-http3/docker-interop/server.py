#!/usr/bin/env python3
"""
Minimal third-party HTTP/3 server (aioquic / lsqpack) for QPACK interop testing.

Why this exists: our Kotlin client's QPACK *encoder* (incl. the dynamic-table eviction tune) is only
truly validated when an INDEPENDENT QPACK *decoder* accepts its output. aioquic decodes with lsqpack —
a different implementation from ours — so if our eviction accounting drifts from the wire contract, this
server's decoder raises QPACK_DECOMPRESSION_FAILED / a decoder-stream error and tears the connection
down, failing the test. Running on localhost over loopback also sidesteps the UDP/443 egress flakiness
that makes the public-endpoint interop test skip on WSL2/CI.

Behaviour: every request gets a 200 whose response headers echo each request header named `x-*` back as
`echo-<name>: <value>`, plus a tiny `ok` body. The client drives a churn of distinct large headers to
force its encoder's dynamic table to fill, evict, and re-reference, then asserts every echo round-trips.

The server advertises a QPACK dynamic table (default 4096; override with QPACK_MAX_TABLE_CAPACITY) so the
client's encoder uses (and evicts from) its dynamic table. Self-signed cert is generated on first run —
the client connects with verifyPeer=false, so the cert identity does not matter.
"""
import asyncio
import datetime
import os
import pathlib

from aioquic.asyncio import serve
from aioquic.asyncio.protocol import QuicConnectionProtocol
from aioquic.h3.connection import H3Connection
from aioquic.h3.events import (
    DataReceived,
    DatagramReceived,
    HeadersReceived,
    WebTransportStreamDataReceived,
)
from aioquic.quic.configuration import QuicConfiguration
from aioquic.quic.events import ProtocolNegotiated, QuicEvent

HOST = os.environ.get("HTTP3_HOST", "0.0.0.0")
PORT = int(os.environ.get("HTTP3_PORT", "4433"))
# Smaller capacity forces eviction with fewer/lighter requests; 4096 is the default both ends pick.
MAX_TABLE_CAPACITY = int(os.environ.get("QPACK_MAX_TABLE_CAPACITY", "4096"))
BLOCKED_STREAMS = int(os.environ.get("QPACK_BLOCKED_STREAMS", "16"))

CERT = pathlib.Path(__file__).with_name("cert.pem")
KEY = pathlib.Path(__file__).with_name("key.pem")


def ensure_cert() -> None:
    if CERT.exists() and KEY.exists():
        return
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), critical=False)
        .sign(key, hashes.SHA256())
    )
    KEY.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    CERT.write_bytes(cert.public_bytes(serialization.Encoding.PEM))


class H3EchoProtocol(QuicConnectionProtocol):
    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._http: H3Connection | None = None
        self._request_headers: dict[int, list[tuple[bytes, bytes]]] = {}
        self._answered: set[int] = set()
        # WebTransport: accepted session (CONNECT) stream ids, and per-bidi-stream accumulators so a
        # stream is echoed back as one chunk on FIN (mirrors the request-on-stream-end QPACK path).
        self._wt_sessions: set[int] = set()
        self._wt_stream_data: dict[int, bytearray] = {}

    def quic_event_received(self, event: QuicEvent) -> None:
        if isinstance(event, ProtocolNegotiated) and event.alpn_protocol == "h3":
            # _max_table_capacity/_blocked_streams are read by H3Connection._init_connection() when it
            # emits SETTINGS, so set them on the instance the constructor path consults.
            H3Connection._max_table_capacity = MAX_TABLE_CAPACITY
            H3Connection._blocked_streams = BLOCKED_STREAMS
            # enable_webtransport advertises ENABLE_CONNECT_PROTOCOL + H3_DATAGRAM + ENABLE_WEBTRANSPORT
            # (the draft-02 toggle aioquic implements) so a WebTransport CONNECT is accepted.
            self._http = H3Connection(self._quic, enable_webtransport=True)
        if self._http is not None:
            for http_event in self._http.handle_event(event):
                self._handle_http_event(http_event)

    def _handle_http_event(self, event) -> None:
        if isinstance(event, HeadersReceived):
            headers = dict(event.headers)
            if headers.get(b":method") == b"CONNECT" and headers.get(b":protocol") == b"webtransport":
                # Accept the WebTransport session: 200, stream stays open (no FIN).
                self._wt_sessions.add(event.stream_id)
                assert self._http is not None
                self._http.send_headers(event.stream_id, [(b":status", b"200")], end_stream=False)
                self.transmit()
                return
            self._request_headers[event.stream_id] = event.headers
            if event.stream_ended:
                self._respond(event.stream_id)
        elif isinstance(event, DataReceived) and event.stream_ended:
            self._respond(event.stream_id)
        elif isinstance(event, WebTransportStreamDataReceived):
            # Echo a bidirectional WebTransport stream back, byte-for-byte, FINishing on the peer's FIN.
            buf = self._wt_stream_data.setdefault(event.stream_id, bytearray())
            buf += event.data
            if event.stream_ended:
                self._quic.send_stream_data(event.stream_id, bytes(buf), end_stream=True)
                del self._wt_stream_data[event.stream_id]
                self.transmit()
        elif isinstance(event, DatagramReceived):
            # Echo a WebTransport datagram back on the same session (stream_id == session/flow id).
            assert self._http is not None
            self._http.send_datagram(event.stream_id, event.data)
            self.transmit()

    def _respond(self, stream_id: int) -> None:
        if stream_id in self._answered:
            return
        self._answered.add(stream_id)
        request = self._request_headers.get(stream_id, [])
        response = [(b":status", b"200")]
        for name, value in request:
            if name.startswith(b"x-"):
                response.append((b"echo-" + name, value))
        assert self._http is not None
        self._http.send_headers(stream_id, response, end_stream=False)
        self._http.send_data(stream_id, b"ok", end_stream=True)
        self.transmit()


async def main() -> None:
    ensure_cert()
    configuration = QuicConfiguration(is_client=False, alpn_protocols=["h3"], max_datagram_frame_size=65536)
    configuration.load_cert_chain(str(CERT), str(KEY))
    await serve(HOST, PORT, configuration=configuration, create_protocol=H3EchoProtocol)
    print(
        f"h3 echo server on udp/{PORT} (QPACK max_table_capacity={MAX_TABLE_CAPACITY}, "
        f"blocked_streams={BLOCKED_STREAMS}, webtransport=on)",
        flush=True,
    )
    await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
